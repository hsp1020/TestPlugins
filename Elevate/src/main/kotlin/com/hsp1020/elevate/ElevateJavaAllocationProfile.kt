package com.hsp1020.elevate

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.os.CancellationSignal
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Explicit, process-wide Java allocation sampling. Unlike HPROF, the Perfetto heap
 * profile records sampled allocation call stacks, including allocations later GC'd.
 * No polling or recording occurs until Start is pressed.
 */
internal object ElevateJavaAllocationProfile {
    private const val TAG = "ElevateJavaAlloc"
    private const val PREFIX = "elevate-java-alloc-"
    private const val DUMP_PREFIX = "elevate-java-heap-"
    private const val RUN_PROPERTY = "hsp1020.java.alloc.run"
    private const val DURATION_MS = 600_000
    // Android's default per-heap-profile limit is 5 minutes. Collect two
    // consecutive segments instead of silently requesting an unsupported 10 minutes.
    private const val SEGMENT_MS = 300_000
    private val lock = Any()
    private val callbackExecutor = Executor { task ->
        Thread(task, "ElevateJavaAllocResult").start()
    }
    private var service: ProfilingManager? = null
    private var listener: Consumer<ProfilingResult>? = null
    private var stopSignal: CancellationSignal? = null
    private var requestTag: String? = null
    private var awaitingResult = false
    private var state = "Idle"
    private var stopRequested = false
    private var traceFile: File? = null
    private var dumping = false
    private var traceResolved = false
    private var heapResolved = false
    private var heapFile: File? = null
    private var runStartedMs = 0L
    private var stopRequestedMs = 0L
    private var heapStartedMs = 0L
    private var heapFinishedMs = 0L
    private var traceCallbackMs = 0L
    private var stopReason = ""
    private var activeSegment = 1
    private var firstResultMs = 0L
    private var secondRequestMs = 0L
    private var segmentCount = 0
    private var sampler: ScheduledExecutorService? = null
    private var sampleIndex = 0
    private var lastGc: LongArray? = null

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        synchronized(lock) {
            if (service != null) return
            val app = context.applicationContext
            val manager = app.getSystemService(ProfilingManager::class.java) ?: return
            val callback = Consumer<ProfilingResult> { result ->
                if (result.tag?.startsWith(PREFIX) == true) onResult(app, result)
            }
            try {
                manager.registerForAllProfilingResults(callbackExecutor, callback)
                listener = callback
                service = manager
            } catch (error: Exception) {
                state = "Profiler registration failed: " + error.javaClass.simpleName
                Log.w(TAG, state, error)
            }
        }
    }


    /** Manual-run-only process memory and ART GC counters. */
    private fun logHeap(phase: String, tag: String?, context: Context? = null, full: Boolean = true) {
        try {
            val runtime = Runtime.getRuntime()
            val stats = arrayOf(
                "art.gc.gc-count", "art.gc.gc-time", "art.gc.blocking-gc-count",
                "art.gc.blocking-gc-time", "art.gc.bytes-allocated", "art.gc.bytes-freed"
            ).map { Debug.getRuntimeStat(it)?.toLongOrNull() ?: -1L }.toLongArray()
            val previous = synchronized(lock) {
                val old = lastGc
                lastGc = stats
                old
            }
            val delta = stats.indices.joinToString(",") { index ->
                if (previous == null || previous[index] < 0L || stats[index] < 0L) "-1"
                else (stats[index] - previous[index]).toString()
            }
            val memory = if (full) Debug.MemoryInfo().also { Debug.getMemoryInfo(it) } else null
            val system = if (full && context != null) {
                ActivityManager.MemoryInfo().also {
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it)
                }
            } else null
            val rss = if (Build.VERSION.SDK_INT >= 35) Debug.getRss() else -1L
            Log.i(TAG, "HEAP phase=$phase run=$tag pid=${Process.myPid()} elapsedMs=${SystemClock.elapsedRealtime()} " +
                "javaUsedKiB=${(runtime.totalMemory() - runtime.freeMemory()) / 1024L} " +
                "javaTotalKiB=${runtime.totalMemory() / 1024L} javaMaxKiB=${runtime.maxMemory() / 1024L} " +
                "nativeAllocatedKiB=${Debug.getNativeHeapAllocatedSize() / 1024L} rssRaw=$rss " +
                "totalPssKiB=${memory?.totalPss ?: -1} graphicsPssKiB=${memory?.getMemoryStat("summary.graphics") ?: "-1"} " +
                "systemAvailKiB=${system?.availMem?.div(1024L) ?: -1} systemLowMemory=${system?.lowMemory ?: false} " +
                "gcCount=${stats[0]} gcTimeMs=${stats[1]} blockingGcCount=${stats[2]} " +
                "blockingGcTimeMs=${stats[3]} allocatedBytes=${stats[4]} freedBytes=${stats[5]} " +
                "deltaGc,gcMs,blockingGc,blockingMs,allocBytes,freedBytes=$delta")
        } catch (error: Exception) {
            Log.w(TAG, "HEAP_UNAVAILABLE phase=$phase reason=${error.javaClass.simpleName}")
        }
    }

    private fun startSampling(context: Context, tag: String) {
        stopSampling()
        sampleIndex = 0
        lastGc = null
        System.setProperty(RUN_PROPERTY, tag)
        ReceiverRegistrationTrace.start(tag)
        logHeap("PROFILE_START", tag, context)
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ElevateMemorySampler").apply { isDaemon = true }
        }
        sampler = executor
        executor.scheduleAtFixedRate({
            try {
                val index = synchronized(lock) { ++sampleIndex }
                logHeap("SAMPLE", tag, context, index % 2 == 0)
            } catch (error: Exception) {
                Log.w(TAG, "Memory sample failed: ${error.javaClass.simpleName}")
            }
        }, 5L, 5L, TimeUnit.SECONDS)
    }

    private fun stopSampling() {
        sampler?.shutdownNow()
        sampler = null
        ReceiverRegistrationTrace.stop()
        if (System.getProperty(RUN_PROPERTY) != null) System.clearProperty(RUN_PROPERTY)
    }

    private fun profileParameters(): Bundle = Bundle().apply {
        putBoolean("KEY_TRACK_JAVA_ALLOCATIONS", true)
        putInt("KEY_DURATION_MS", SEGMENT_MS)
        putInt("KEY_SIZE_KB", 65_536)
        putLong("KEY_SAMPLING_INTERVAL_BYTES", 262_144L)
    }

    fun start(context: Context): String {
        if (Build.VERSION.SDK_INT < 35) return "Requires Android 15 or newer"
        initialize(context)
        return synchronized(lock) {
            if (awaitingResult) return@synchronized "Already recording or awaiting a result"
            val manager = service ?: return@synchronized state
            val tag = PREFIX + System.currentTimeMillis()
            val signal = CancellationSignal()
            val params = profileParameters()
            try {
                stopSignal = signal
                requestTag = tag
                awaitingResult = true
                stopRequested = false
                traceFile = null
                heapFile = null
                dumping = false
                traceResolved = false
                heapResolved = false
                runStartedMs = SystemClock.elapsedRealtime()
                stopRequestedMs = 0L
                heapStartedMs = 0L
                heapFinishedMs = 0L
                traceCallbackMs = 0L
                stopReason = ""
                activeSegment = 1
                firstResultMs = 0L
                secondRequestMs = 0L
                segmentCount = 0
                manager.requestProfiling(
                    ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                    params, tag, signal, null, null
                )
                state = "Recording requested (up to 600 s in two 300 s segments). Reproduce, then Stop."
                Log.i(TAG, "REQUEST tag=" + tag + " java=true intervalBytes=262144 bufferKb=65536 " +
                    "durationMs=$DURATION_MS segmentMs=$SEGMENT_MS")
                startSampling(context.applicationContext, tag)
                state
            } catch (error: Exception) {
                stopSampling()
                stopSignal?.cancel()
                stopSignal = null
                requestTag = null
                awaitingResult = false
                stopRequested = false
                traceFile = null
                dumping = false
                state = "Request failed: " + error.javaClass.simpleName
                Log.w(TAG, state, error)
                state
            }
        }
    }


    /** Stop requests Perfetto finalization and immediately captures the matching heap. */
    fun stop(context: Context): String = stop(context, "manual")

    private fun stop(context: Context, reason: String): String {
        val (tag, signal) = synchronized(lock) {
            val activeTag = requestTag ?: return state
            if (!awaitingResult || stopRequested) return state
            stopRequested = true
            stopRequestedMs = SystemClock.elapsedRealtime()
            stopReason = reason
            val activeSignal = stopSignal
            stopSignal = null
            state = "Finishing Perfetto and capturing Java heap dump..."
            activeTag to activeSignal
        }
        Log.i(TAG, "TIMING phase=STOP_REQUEST run=$tag reason=$reason wallMs=${System.currentTimeMillis()} " +
            "elapsedMs=${SystemClock.elapsedRealtime()} sinceStartMs=${stopRequestedMs - runStartedMs}")
        logHeap("PROFILE_STOP", tag, context.applicationContext)
        stopSampling()
        // The profiling result is asynchronous. Never wait for its file copy before HPROF.
        if (reason == "manual") {
            try {
                signal?.cancel()
            } catch (error: Exception) {
                Log.w(TAG, "Perfetto stop request failed run=$tag", error)
            }
        }
        captureHeap(context.applicationContext, tag)
        return statusText()
    }

    private fun captureHeap(context: Context, tag: String) {
        synchronized(lock) {
            if (requestTag != tag || dumping || heapResolved) return
            dumping = true
        }
        Thread({
            val dir = File(context.filesDir, "elevate-java-allocation-profiles")
            var temp: File? = null
            var saved: File? = null
            try {
                require(dir.isDirectory || dir.mkdirs()) { "Cannot create profile directory" }
                temp = File.createTempFile("heap-", ".hprof", dir)
                val start = SystemClock.elapsedRealtime()
                synchronized(lock) { if (requestTag == tag) heapStartedMs = start }
                Log.i(TAG, "TIMING phase=HPROF_START run=$tag wallMs=${System.currentTimeMillis()} " +
                    "elapsedMs=$start afterStopMs=${start - stopRequestedMs}")
                Debug.dumpHprofData(temp.absolutePath)
                val end = SystemClock.elapsedRealtime()
                synchronized(lock) { if (requestTag == tag) heapFinishedMs = end }
                Log.i(TAG, "TIMING phase=HPROF_END run=$tag wallMs=${System.currentTimeMillis()} " +
                    "elapsedMs=$end durationMs=${end - start}")
                require(temp.isFile && temp.length() > 0L) { "Heap dump is empty" }
                val target = File(dir, tag.replaceFirst(PREFIX, DUMP_PREFIX) + ".hprof")
                require(temp.renameTo(target)) { "Cannot save heap dump" }
                saved = target
                temp = null
            } catch (error: Throwable) {
                Log.e(TAG, "Java heap dump failed run=$tag", error)
            } finally {
                temp?.delete()
                synchronized(lock) {
                    if (requestTag == tag) {
                        heapFile = saved
                        heapResolved = true
                    }
                }
                Log.i(TAG, "RESULT run=$tag phase=HPROF saved=${saved != null}")
                logHeap("HEAP_DUMP_RESULT", tag, context)
                finishSessionIfReady(context, tag)
            }
        }, "ElevateJavaHeapDump").start()
    }

    private fun finishSessionIfReady(context: Context, tag: String) {
        val summary = synchronized(lock) {
            if (tag != requestTag || !traceResolved || !heapResolved) return
            val traceOk = traceFile != null
            val heapOk = heapFile != null
            val timing = "run=$tag\\nstopReason=$stopReason\\n" +
                "requestedDurationMs=$DURATION_MS\\nsegmentDurationMs=$SEGMENT_MS\\n" +
                "segmentsCaptured=$segmentCount\\nfirstResultElapsedMs=$firstResultMs\\n" +
                "secondRequestElapsedMs=$secondRequestMs\\n" +
                "runStartElapsedMs=$runStartedMs\\nstopRequestElapsedMs=$stopRequestedMs\\n" +
                "perfettoResultCallbackElapsedMs=$traceCallbackMs\\n" +
                "hprofStartElapsedMs=$heapStartedMs\\nhprofEndElapsedMs=$heapFinishedMs\\n" +
                "stopToHprofStartMs=${if (heapStartedMs > 0L) heapStartedMs - stopRequestedMs else -1L}\\n" +
                "stopToPerfettoCallbackMs=${if (traceCallbackMs > 0L) traceCallbackMs - stopRequestedMs else -1L}\\n" +
                "perfettoSaved=$traceOk\\nhprofSaved=$heapOk\\n" +
                "NOTE: Perfetto actual end time is not exposed by ProfilingManager; " +
                "the stop request and result callback are separate timestamps.\\n"
            state = when {
                traceOk && heapOk && segmentCount >= 2 ->
                    "Saved both 300 s Perfetto segments + HPROF for run=$tag; ready to export"
                traceOk && heapOk ->
                    "Saved $segmentCount/2 Perfetto segments + HPROF; 600 s profile incomplete"
                traceOk -> "Perfetto saved; HPROF capture failed"
                heapOk -> "HPROF saved; Perfetto result failed"
                else -> "Perfetto and HPROF capture failed"
            }
            requestTag = null
            awaitingResult = false
            stopSignal = null
            dumping = false
            Triple(state, timing, traceOk || heapOk)
        }
        val (message, timing, anyFile) = summary
        if (anyFile) {
            runCatching {
                File(context.filesDir, "elevate-java-allocation-profiles")
                    .resolve("elevate-java-timing-" + tag.removePrefix(PREFIX) + ".txt")
                    .writeText(timing)
                pruneOldSessions(context)
            }.onFailure { Log.w(TAG, "Timing manifest save failed run=$tag", it) }
        }
        Log.i(TAG, "SESSION_RESULT run=$tag $message")
    }

    fun timingFile(context: Context, trace: File): File? =
        File(context.applicationContext.filesDir, "elevate-java-allocation-profiles")
            .resolve("elevate-java-timing-" +
                trace.name.removePrefix(PREFIX).removeSuffix(".perfetto-trace") + ".txt")
            .takeIf { it.isFile }

    /** Keep complete paired sessions rather than deleting one half of a pair. */
    private fun pruneOldSessions(context: Context) {
        val dir = File(context.filesDir, "elevate-java-allocation-profiles")
        val ids = dir.listFiles()?.mapNotNull { file ->
            when {
                file.name.startsWith(PREFIX) -> file.name.removePrefix(PREFIX).substringBefore('.')
                file.name.startsWith(DUMP_PREFIX) -> file.name.removePrefix(DUMP_PREFIX).substringBefore('.')
                file.name.startsWith("elevate-java-timing-") ->
                    file.name.removePrefix("elevate-java-timing-").substringBefore('.')
                else -> null
            }
        }?.distinct()?.sortedDescending() ?: return
        val discarded = ids.drop(4).toSet()
        if (discarded.isEmpty()) return
        dir.listFiles()?.forEach { file ->
            val id = when {
                file.name.startsWith(PREFIX) -> file.name.removePrefix(PREFIX).substringBefore('.')
                file.name.startsWith(DUMP_PREFIX) -> file.name.removePrefix(DUMP_PREFIX).substringBefore('.')
                file.name.startsWith("elevate-java-timing-") ->
                    file.name.removePrefix("elevate-java-timing-").substringBefore('.')
                else -> null
            }
            if (id in discarded) file.delete()
        }
    }

    /** Remove only saved diagnostic files; never interrupt an active profiling session. */
    fun deleteSavedProfiles(context: Context): String = synchronized(lock) {
        if (awaitingResult || dumping || requestTag != null) {
            return@synchronized "Profiling is active or still saving. Delete after it finishes."
        }
        val dir = File(context.applicationContext.filesDir, "elevate-java-allocation-profiles")
        if (!dir.exists()) return@synchronized "No saved profiling files."
        val files = dir.listFiles()?.filter { it.isFile }
            ?: return@synchronized "Could not read saved profiling files."
        var deleted = 0
        var freedBytes = 0L
        var failed = 0
        for (file in files) {
            val bytes = file.length()
            if (file.delete()) {
                deleted++
                freedBytes += bytes
            } else failed++
        }
        val message = "Deleted $deleted files (${freedBytes / (1024L * 1024L)} MiB freed)" +
            if (failed > 0) "; $failed files could not be deleted." else "."
        state = message
        message
    }

    fun profiles(context: Context): List<File> =
        File(context.applicationContext.filesDir, "elevate-java-allocation-profiles")
            .listFiles()?.filter {
                it.isFile && (it.name.startsWith(PREFIX) || it.name.startsWith(DUMP_PREFIX)) &&
                    (it.name.endsWith(".perfetto-trace") || it.name.endsWith(".hprof") ||
                        it.name.endsWith(".hprof.gz")) && it.length() > 0L
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Debug-only lifecycle trace; never retains a player, Activity, or view. */
    fun traceHook(
        hook: String, event: String, activity: android.app.Activity? = null,
        fragment: androidx.fragment.app.Fragment? = null, detail: String = ""
    ) {
        val run = System.getProperty(RUN_PROPERTY) ?: return
        Log.i("JavaAllocCause",
            "run=$run module=Elevate phase=HOOK_LIFECYCLE hook=$hook event=$event " +
                "activityId=${activity?.let { System.identityHashCode(it) } ?: -1} " +
                "fragmentId=${fragment?.let { System.identityHashCode(it) } ?: -1} " +
                "viewId=${fragment?.view?.let { System.identityHashCode(it) } ?: -1} $detail")
    }

    /** Correlate Activity stop/destroy with the next memory/PSS snapshot off the UI thread. */
    fun checkpoint(context: Context, phase: String) {
        if (System.getProperty(RUN_PROPERTY) == null) return
        val (tag, executor) = synchronized(lock) { requestTag to sampler }
        if (tag == null || executor == null) return
        runCatching {
            executor.execute { logHeap(phase, tag, context.applicationContext) }
        }
    }

    fun statusText(): String = synchronized(lock) { state }


    /** Return only complete Perfetto/HPROF pairs for a single-file ZIP export. */
    fun completedSessions(context: Context): List<Pair<File, File>> {
        val files = profiles(context)
        val dumps = files.filter { it.name.startsWith(DUMP_PREFIX) && it.name.endsWith(".hprof") }
            .associateBy { it.name.removePrefix(DUMP_PREFIX).removeSuffix(".hprof") }
        return files.filter { it.name.startsWith(PREFIX) && it.name.endsWith(".perfetto-trace") }
            .mapNotNull { trace ->
                val sessionId = trace.name.removePrefix(PREFIX).removeSuffix(".perfetto-trace")
                dumps[sessionId]?.let { trace to it }
            }
    }

    private fun onResult(context: Context, result: ProfilingResult) {
        val resultTag = result.tag ?: return
        val tag = synchronized(lock) { requestTag } ?: return
        val part = when (resultTag) {
            tag -> 1
            "${tag}-part2" -> 2
            else -> return
        }
        val callbackTime = SystemClock.elapsedRealtime()
        Log.i(TAG, "TIMING phase=PERFETTO_CALLBACK run=$tag segment=$part " +
            "elapsedMs=$callbackTime wallMs=${System.currentTimeMillis()} " +
            "errorCode=${result.errorCode}")
        val dir = File(context.filesDir, "elevate-java-allocation-profiles")
        var savedSegment: File? = null
        try {
            if (result.errorCode == ProfilingResult.ERROR_NONE) {
                val source = File(result.resultFilePath ?: error("Missing result path"))
                require(source.isFile && source.length() > 0L) { "Result file is empty" }
                require(dir.isDirectory || dir.mkdirs()) { "Cannot create profile directory" }
                val target = File(dir, "${tag}.segment${part}.tmp")
                val temp = File.createTempFile("segment-", ".tmp", dir)
                try {
                    source.inputStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    require(temp.length() > 0L) { "Empty segment" }
                    if (target.exists()) require(target.delete()) { "Cannot replace segment" }
                    require(temp.renameTo(target)) { "Cannot save segment" }
                    savedSegment = target
                } finally {
                    temp.delete()
                }
                synchronized(lock) {
                    if (requestTag == tag) segmentCount++
                }
                Log.i(TAG, "SEGMENT_RESULT run=$tag segment=$part savedBytes=${target.length()}")
            } else {
                Log.e(TAG, "SEGMENT_RESULT run=$tag segment=$part failed=" +
                    "${result.errorCode} ${result.errorMessage.orEmpty()}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Perfetto segment save failed run=$tag segment=$part", error)
        }

        if (part == 1) {
            synchronized(lock) { if (requestTag == tag) firstResultMs = callbackTime }
            val shouldContinue = synchronized(lock) {
                requestTag == tag && !stopRequested && savedSegment != null
            }
            if (shouldContinue) {
                try {
                    val manager = synchronized(lock) { service }
                        ?: error("Profiler service unavailable for second segment")
                    val signal = CancellationSignal()
                    synchronized(lock) {
                        stopSignal = signal
                        activeSegment = 2
                        secondRequestMs = SystemClock.elapsedRealtime()
                        state = "Recording second 300 s allocation segment..."
                    }
                    manager.requestProfiling(
                        ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                        profileParameters(), "${tag}-part2", signal, null, null
                    )
                    // Stop may race with the first callback's second request.
                    if (synchronized(lock) { stopRequested }) signal.cancel()
                    Log.i(TAG, "SEGMENT_START run=$tag segment=2 elapsedMs=$secondRequestMs")
                    return
                } catch (error: Exception) {
                    Log.w(TAG, "Second 300 s profile could not start run=$tag", error)
                    // Preserve the first segment and take an HPROF immediately.
                }
            }
        }
        // Manual Stop, second segment completion, or an early platform failure.
        if (!synchronized(lock) { stopRequested }) stop(context, "system-result")
        synchronized(lock) { if (requestTag == tag) traceCallbackMs = callbackTime }
        var combined: File? = null
        try {
            val parts = (1..2).map { File(dir, "${tag}.segment${it}.tmp") }
                .filter { it.isFile && it.length() > 0L }
            if (parts.isNotEmpty()) {
                val target = File(dir, tag + ".perfetto-trace")
                val temp = File.createTempFile("profile-", ".tmp", dir)
                try {
                    temp.outputStream().buffered().use { output ->
                        parts.forEach { partFile ->
                            partFile.inputStream().buffered().use { it.copyTo(output) }
                        }
                    }
                    require(temp.length() > 0L) { "Combined profile is empty" }
                    if (target.exists()) require(target.delete()) { "Cannot replace profile" }
                    require(temp.renameTo(target)) { "Cannot finish profile" }
                    combined = target
                } finally {
                    temp.delete()
                }
            }
            Log.i(TAG, "PROFILE_SEGMENTS run=$tag captured=${parts.size} requested=2 " +
                "combinedBytes=${combined?.length() ?: 0L}")
        } catch (error: Exception) {
            Log.e(TAG, "Perfetto segment combination failed run=$tag", error)
        } finally {
            if (combined != null) {
                (1..2).forEach { File(dir, "${tag}.segment${it}.tmp").delete() }
            }
        }
        synchronized(lock) {
            if (requestTag == tag) {
                traceFile = combined
                traceResolved = true
                state = if (heapResolved) state else "Perfetto processed; waiting for HPROF..."
            }
        }
        logHeap("PROFILE_RESULT", tag, context)
        finishSessionIfReady(context, tag)
    }

    fun shutdown() {
        stopSampling()
        synchronized(lock) {
            stopSignal?.cancel()
            stopSignal = null
            val manager = service
            val callback = listener
            if (manager != null && callback != null) {
                runCatching { manager.unregisterForAllProfilingResults(callback) }
            }
            service = null
            listener = null
            requestTag = null
            awaitingResult = false
            stopRequested = false
            traceFile = null
            dumping = false
            traceResolved = false
            heapResolved = false
            heapFile = null
            activeSegment = 1
            segmentCount = 0
            state = "Idle"
        }
    }
}
