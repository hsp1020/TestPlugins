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
                if (result.tag?.startsWith(PREFIX) == true || result.tag?.startsWith(DUMP_PREFIX) == true) onResult(app, result)
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
                manager.requestProfiling(
                    ProfilingManager.PROFILING_TYPE_HEAP_PROFILE,
                    params, tag, signal, null, null
                )
                stopSignal = signal
                requestTag = tag
                awaitingResult = true
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
                state = "Request failed: " + error.javaClass.simpleName
                Log.w(TAG, state, error)
                state
            }
        }
    }

    fun stop(): String {
        val signal = synchronized(lock) {
            val current = stopSignal ?: return@synchronized null
            stopSignal = null
            state = "Stopping; waiting for the system result..."
            current
        } ?: return statusText()
        return try {
            logHeap("PROFILE_STOP", synchronized(lock) { requestTag })
            stopSampling()
            signal.cancel()
            synchronized(lock) { state }
        } catch (error: Exception) {
            synchronized(lock) { state = "Stop failed: " + error.javaClass.simpleName }
            Log.w(TAG, "Stop failed", error)
            synchronized(lock) { state }
        }
    }


    /** One-shot HPROF requested separately from the continuous allocation sampler. */
    fun heapDump(context: Context): String {
        if (Build.VERSION.SDK_INT < 35) return "Requires Android 15 or newer"
        initialize(context)
        return synchronized(lock) {
            if (awaitingResult) return@synchronized "Finish the previous profile first"
            val manager = service ?: return@synchronized state
            val tag = DUMP_PREFIX + System.currentTimeMillis()
            try {
                manager.requestProfiling(
                    ProfilingManager.PROFILING_TYPE_JAVA_HEAP_DUMP,
                    Bundle(), tag, null, null, null
                )
                requestTag = tag
                awaitingResult = true
                state = "Java heap dump requested; may pause playback. Wait for result."
                Log.i(TAG, "DUMP_REQUEST tag=$tag pid=${Process.myPid()}")
                logHeap("HEAP_DUMP_REQUEST", tag, context.applicationContext)
                state
            } catch (error: Exception) {
                state = "Heap dump request failed: ${error.javaClass.simpleName}"
                Log.w(TAG, state, error)
                state
            }
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

    private fun onResult(context: Context, result: ProfilingResult) {
        val tag = result.tag ?: return
        val message = try {
            if (result.errorCode != ProfilingResult.ERROR_NONE) {
                "Profiling failed: code=" + result.errorCode + " " + result.errorMessage.orEmpty()
            } else {
                val path = result.resultFilePath ?: error("Missing result path")
                val source = File(path)
                require(source.isFile && source.length() > 0L) { "Result file is empty" }
                val dir = File(context.filesDir, "elevate-java-allocation-profiles")
                require(dir.isDirectory || dir.mkdirs()) { "Cannot create profile directory" }
                val suffix = if (tag.startsWith(DUMP_PREFIX)) {
                    if (source.name.endsWith(".gz")) ".hprof.gz" else ".hprof"
                } else ".perfetto-trace"
                val target = File(dir, tag + suffix)
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
                profiles(context).drop(4).forEach { it.delete() }
                "Saved " + target.name + " (" + target.length() / 1024L + " KiB; completeness unverified)"
            }
        } catch (error: Exception) {
            Log.e(TAG, "Result processing failed tag=" + tag, error)
            "Result save failed: " + error.javaClass.simpleName
        }
        synchronized(lock) {
            if (requestTag == tag) {
                requestTag = null
                stopSignal = null
                awaitingResult = false
            }
            state = message
        }
        Log.i(TAG, "RESULT tag=" + tag + " " + message)
        if (tag.startsWith(PREFIX)) stopSampling()
        logHeap("PROFILE_RESULT", tag, context)
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
            state = "Idle"
        }
    }
}
