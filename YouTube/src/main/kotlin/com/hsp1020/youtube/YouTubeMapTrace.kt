package com.hsp1020.youtube

import android.os.SystemClock
import android.util.Log

/** Temporary, bounded stack sampler to identify callers of AbstractMap.toString(). */
internal object YouTubeMapTrace {
    private const val TAG = "YouTubeMapTrace"
    private const val WINDOW_MS = 60_000L
    private const val SAMPLE_MS = 50L
    private const val MAX_ORIGINS = 20
    private val lock = Any()
    @Volatile private var activeUntilMs = 0L
    private var running = false

    fun arm(entry: String) {
        synchronized(lock) {
            activeUntilMs = SystemClock.elapsedRealtime() + WINDOW_MS
            if (!running) startLocked(entry)
        }
    }

    private fun startLocked(entry: String) {
        running = true
        Thread({
            val origins = HashSet<String>()
            var scans = 0
            var hits = 0
            var failed = false
            try {
                Log.i(TAG, "START entry=$entry intervalMs=$SAMPLE_MS windowMs=$WINDOW_MS")
                while (SystemClock.elapsedRealtime() < activeUntilMs) {
                    scans++
                    for ((thread, stack) in Thread.getAllStackTraces()) {
                        val outer = stack.indexOfLast {
                            it.className == "java.util.AbstractMap" && it.methodName == "toString"
                        }
                        if (outer < 0) continue
                        val mapDepth = stack.count {
                            it.className == "java.util.AbstractMap" && it.methodName == "toString"
                        }
                        if (mapDepth < 2) continue
                        hits++
                        val callers = stack.drop(outer + 1)
                        val origin = callers.firstOrNull {
                            it.className.startsWith("com.hsp1020.") ||
                                it.className.startsWith("com.lagradost.")
                        } ?: callers.firstOrNull {
                            !it.className.startsWith("java.") &&
                                !it.className.startsWith("kotlin.") &&
                                !it.className.startsWith("android.")
                        } ?: callers.lastOrNull()
                        val key = origin?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
                            ?: "UNKNOWN_STACK_TRUNCATED"
                        if (origins.size < MAX_ORIGINS && origins.add(key)) {
                            val chain = callers.take(20).joinToString(" <- ") {
                                "${it.className}.${it.methodName}:${it.lineNumber}"
                            }
                            Log.w(TAG, "HIT thread=${thread.name} mapDepth=$mapDepth origin=$key callers=$chain")
                        }
                    }
                    Thread.sleep(SAMPLE_MS)
                }
            } catch (e: Exception) {
                failed = true
                Log.e(TAG, "SAMPLER_ERROR ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                Log.i(TAG, "END scans=$scans hits=$hits origins=${origins.size}")
                synchronized(lock) {
                    running = false
                    if (!failed && SystemClock.elapsedRealtime() < activeUntilMs) {
                        startLocked("extended")
                    }
                }
            }
        }, "YouTubeMapTrace").apply { isDaemon = true }.start()
    }
}
