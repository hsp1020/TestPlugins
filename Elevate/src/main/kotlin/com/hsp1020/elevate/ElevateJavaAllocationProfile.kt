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
    private const val DURATION_MS = 180_000
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
        if (System.getProperty(RUN_PROPERTY) != null) System.clearProperty(RUN_PROPERTY)
    }

    fun start(context: Context): String {
        if (Build.VERSION.SDK_INT < 35) return "Requires Android 15 or newer"
        initialize(context)
        return synchronized(lock) {
            if (awaitingResult) return@synchronized "Already recording or awaiting a result"
            val manager = service ?: return@synchronized state
            val tag = PREFIX + System.currentTimeMillis()
            val signal = CancellationSignal()
            val params = Bundle().apply {
                // AndroidX Profiling.HeapProfileRequestBuilder's platform parameter keys.
                putBoolean("KEY_TRACK_JAVA_ALLOCATIONS", true)
                putInt("KEY_DURATION_MS", DURATION_MS)
                putInt("KEY_SIZE_KB", 65_536)
                putLong("KEY_SAMPLING_INTERVAL_BYTES", 262_144L)
            }
            try {
                stopSignal = signal
                requestTag = tag
                awaitingResult = true
                stopRequested = false
                traceFile = null
                dumping = false
                manager.requestProfiling(
                    ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                    params, tag, signal, null, null
                )
                state = "Recording requested (max 180 s). Reproduce, then Stop."
                Log.i(TAG, "REQUEST tag=" + tag + " java=true intervalBytes=262144 bufferKb=65536 durationMs=" + DURATION_MS)
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


    /** One Stop finishes Perfetto; its successful result automatically starts the HPROF. */
    fun stop(context: Context): String {
        val (tag, signal) = synchronized(lock) {
            val activeTag = requestTag ?: return state
            if (!awaitingResult || stopRequested) return state
            stopRequested = true
            val activeSignal = stopSignal
            stopSignal = null
            state = "Finishing Perfetto; Java heap dump follows automatically..."
            activeTag to activeSignal
        }
        logHeap("PROFILE_STOP", tag, context.applicationContext)
        stopSampling()
        try {
            signal?.cancel()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to stop allocation profile", error)
        }
        // The 180-second profile may have finished before Stop was pressed.
        captureHeapAfterTrace(context.applicationContext, tag)
        return statusText()
    }

    /** Only runs after a valid Perfetto result is saved and Stop was requested. */
    private fun captureHeapAfterTrace(context: Context, tag: String) {
        synchronized(lock) {
            if (requestTag != tag || !stopRequested || traceFile == null || dumping) return
            dumping = true
            state = "Perfetto saved; capturing Java heap dump..."
        }
        Thread({
            val dir = File(context.filesDir, "elevate-java-allocation-profiles")
            var temp: File? = null
            val message = try {
                require(dir.isDirectory || dir.mkdirs()) { "Cannot create profile directory" }
                temp = File.createTempFile("heap-", ".hprof", dir)
                Debug.dumpHprofData(temp.absolutePath)
                require(temp.isFile && temp.length() > 0L) { "Heap dump is empty" }
                val target = File(dir, tag.replaceFirst(PREFIX, DUMP_PREFIX) + ".hprof")
                require(temp.renameTo(target)) { "Cannot save heap dump" }
                temp = null
                pruneOldSessions(context)
                "Saved Perfetto + HPROF for run=$tag; ready to export as ZIP"
            } catch (error: Throwable) {
                Log.e(TAG, "Java heap dump failed run=$tag", error)
                "Perfetto saved; HPROF failed: ${error.javaClass.simpleName}"
            } finally {
                temp?.delete()
            }
            synchronized(lock) {
                if (requestTag == tag) {
                    state = message
                    stopSignal = null
                    requestTag = null
                    awaitingResult = false
                    stopRequested = false
                    traceFile = null
                    dumping = false
                }
            }
            Log.i(TAG, "RESULT run=$tag $message")
            logHeap("HEAP_DUMP_RESULT", tag, context)
        }, "ElevateJavaHeapDump").start()
    }

    /** Keep complete paired sessions rather than deleting one half of a pair. */
    private fun pruneOldSessions(context: Context) {
        val dir = File(context.filesDir, "elevate-java-allocation-profiles")
        val ids = dir.listFiles()?.mapNotNull { file ->
            when {
                file.name.startsWith(PREFIX) -> file.name.removePrefix(PREFIX).substringBefore('.')
                file.name.startsWith(DUMP_PREFIX) -> file.name.removePrefix(DUMP_PREFIX).substringBefore('.')
                else -> null
            }
        }?.distinct()?.sortedDescending() ?: return
        val discarded = ids.drop(4).toSet()
        if (discarded.isEmpty()) return
        dir.listFiles()?.forEach { file ->
            val id = when {
                file.name.startsWith(PREFIX) -> file.name.removePrefix(PREFIX).substringBefore('.')
                file.name.startsWith(DUMP_PREFIX) -> file.name.removePrefix(DUMP_PREFIX).substringBefore('.')
                else -> null
            }
            if (id in discarded) file.delete()
        }
    }

    fun profiles(context: Context): List<File> =
        File(context.applicationContext.filesDir, "elevate-java-allocation-profiles")
            .listFiles()?.filter {
                it.isFile && (it.name.startsWith(PREFIX) || it.name.startsWith(DUMP_PREFIX)) &&
                    (it.name.endsWith(".perfetto-trace") || it.name.endsWith(".hprof") ||
                        it.name.endsWith(".hprof.gz")) && it.length() > 0L
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

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
        val tag = result.tag ?: return
        synchronized(lock) { if (tag != requestTag) return }
        var savedFile: File? = null
        val message = try {
            if (result.errorCode != ProfilingResult.ERROR_NONE) {
                "Perfetto failed: code=${result.errorCode} ${result.errorMessage.orEmpty()}"
            } else {
                val path = result.resultFilePath ?: error("Missing result path")
                val source = File(path)
                require(source.isFile && source.length() > 0L) { "Result file is empty" }
                val dir = File(context.filesDir, "elevate-java-allocation-profiles")
                require(dir.isDirectory || dir.mkdirs()) { "Cannot create profile directory" }
                val target = File(dir, tag + ".perfetto-trace")
                val temp = File.createTempFile("profile-", ".tmp", dir)
                try {
                    source.inputStream().use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    require(temp.length() > 0L) { "Copied profile is empty" }
                    if (target.exists()) require(target.delete()) { "Cannot replace profile" }
                    require(temp.renameTo(target)) { "Cannot finish profile copy" }
                } finally {
                    temp.delete()
                }
                savedFile = target
                "Saved ${target.name} (${target.length() / 1024L} KiB; completeness unverified)"
            }
        } catch (error: Exception) {
            Log.e(TAG, "Perfetto result processing failed tag=$tag", error)
            "Perfetto save failed: ${error.javaClass.simpleName}"
        }
        stopSampling()
        val shouldDump = synchronized(lock) {
            if (requestTag != tag) return@synchronized false
            stopSignal = null
            traceFile = savedFile
            if (savedFile == null) {
                state = message
                requestTag = null
                awaitingResult = false
                stopRequested = false
                false
            } else {
                state = if (stopRequested) "Perfetto saved; capturing Java heap dump..."
                        else "Perfetto saved; press Stop to capture HPROF."
                stopRequested
            }
        }
        Log.i(TAG, "RESULT tag=$tag $message")
        logHeap("PROFILE_RESULT", tag, context)
        if (shouldDump) captureHeapAfterTrace(context, tag)
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
            state = "Idle"
        }
    }
}
