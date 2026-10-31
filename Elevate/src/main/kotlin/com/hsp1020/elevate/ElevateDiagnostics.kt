package com.hsp1020.elevate

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File

/** Opt-in process-wide diagnostics. Neither events nor file generation poll the UI. */
internal object ElevateDiagnostics {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var tracing = false
    private var dumping = false
    private var traceFile: File? = null
    @Volatile private var heapFile: File? = null

    fun probe(context: Context, event: String, details: String = "") {
        if (!context.getSharedPreferences("CSProbe", Context.MODE_PRIVATE)
                .getBoolean("enabled", false)) return
        val r = Runtime.getRuntime()
        Log.i("CSProbe", "run=${Process.myPid()} module=Elevate event=$event $details " +
            "heapMiB=${(r.totalMemory() - r.freeMemory()) / 1048576L} " +
            "nativeMiB=${Debug.getNativeHeapAllocatedSize() / 1048576L} " +
            "gc=${Debug.getRuntimeStat("art.gc.gc-count") ?: "?"} timeMs=${SystemClock.elapsedRealtime()}")
    }

    fun startTrace(context: Context): String = synchronized(lock) {
        if (tracing || dumping) return@synchronized "Diagnostics already running"
        val file = File(context.cacheDir, "csprobe-${System.currentTimeMillis()}.trace")
        try {
            Debug.startMethodTracingSampling(file.absolutePath, 8 * 1024 * 1024, 3000)
            tracing = true
            traceFile?.takeIf { it !== file }?.delete()
            traceFile = file
            probe(context, "trace_begin")
            main.postDelayed({ stopTrace() }, 12000L)
            "Trace started for 12s. Reproduce YouTube playback/PiP now."
        } catch (e: Exception) {
            Log.e("CSProbe", "Cannot start trace", e)
            "Trace failed: ${e.javaClass.simpleName}"
        }
    }

    fun stopTrace(): String = synchronized(lock) {
        if (!tracing) return@synchronized "No active trace"
        try {
            Debug.stopMethodTracing()
            tracing = false
            "Trace saved. Reopen Elevate settings and save last .trace."
        } catch (e: Exception) {
            tracing = false
            Log.e("CSProbe", "Cannot stop trace", e)
            "Trace stop failed: ${e.javaClass.simpleName}"
        }
    }

    fun lastTraceFile(): File? = synchronized(lock) {
        traceFile?.takeIf { !tracing && it.isFile && it.length() > 0L }
    }

    fun lastHeapFile(): File? = heapFile?.takeIf { it.isFile && it.length() > 0L }

    fun captureHeap(context: Context, done: (String) -> Unit): String {
        synchronized(lock) {
            if (tracing || dumping) return "Another diagnostic is active"
            val r = Runtime.getRuntime()
            val used = r.totalMemory() - r.freeMemory()
            if (r.maxMemory() - used < 128L * 1048576L)
                return "Heap close to limit: capture after returning to Home"
            dumping = true
        }
        Thread({
            val file = File(context.cacheDir, "csprobe-${System.currentTimeMillis()}.hprof")
            val result = runCatching { Debug.dumpHprofData(file.absolutePath) }
            synchronized(lock) {
                dumping = false
                if (result.isSuccess && file.isFile && file.length() > 0L) {
                    heapFile?.takeIf { it !== file }?.delete()
                    heapFile = file
                }
            }
            result.exceptionOrNull()?.let { Log.e("CSProbe", "HPROF capture failed", it) }
            main.post {
                done(if (result.isSuccess) "HPROF ready. Reopen settings and save last .hprof."
                    else "HPROF failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
            }
        }, "Elevate-HPROF").start()
        return "Capturing HPROF; the app may briefly pause."
    }
}
