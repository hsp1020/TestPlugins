package com.hsp1020.elevate

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Explicit, process-wide Java allocation sampling. Unlike HPROF, the Perfetto heap
 * profile records sampled allocation call stacks, including allocations later GC'd.
 * No polling or recording occurs until Start is pressed.
 */
internal object ElevateJavaAllocationProfile {
    private const val TAG = "ElevateJavaAlloc"
    private const val PREFIX = "elevate-java-alloc-"
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

    /** The live device's process memory, independent of sampled allocations. */
    private fun logHeap(phase: String, tag: String?) {
        try {
            val runtime = Runtime.getRuntime()
            val info = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(info)
            Log.i(TAG, "HEAP phase=$phase tag=$tag javaUsedKiB=" +
                (runtime.totalMemory() - runtime.freeMemory()) / 1024L +
                " nativeAllocatedKiB=" + android.os.Debug.getNativeHeapAllocatedSize() / 1024L +
                " totalPssKiB=" + info.totalPss)
        } catch (error: Exception) {
            Log.w(TAG, "HEAP_UNAVAILABLE phase=$phase reason=" + error.javaClass.simpleName)
        }
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
                logHeap("PROFILE_START", tag)
                state
            } catch (error: Exception) {
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
            signal.cancel()
            synchronized(lock) { state }
        } catch (error: Exception) {
            synchronized(lock) { state = "Stop failed: " + error.javaClass.simpleName }
            Log.w(TAG, "Stop failed", error)
            synchronized(lock) { state }
        }
    }

    fun profiles(context: Context): List<File> =
        File(context.applicationContext.filesDir, "elevate-java-allocation-profiles")
            .listFiles()?.filter {
                it.isFile && it.name.startsWith(PREFIX) &&
                    it.name.endsWith(".perfetto-trace") && it.length() > 0L
            }?.sortedByDescending { it.name } ?: emptyList()

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
        logHeap("PROFILE_RESULT", tag)
    }

    fun shutdown() {
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
        }
    }
}
