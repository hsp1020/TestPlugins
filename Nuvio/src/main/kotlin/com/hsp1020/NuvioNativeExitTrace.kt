package com.hsp1020

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.lagradost.api.Log

/** Best-effort previous-process tombstone on startup; Android may not retain a trace. */
internal object NuvioNativeExitTrace {
    private const val TAG = "NuvioNativeTrace"

    fun report(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val previous = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .firstOrNull {
                    it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        it.reason == ApplicationExitInfo.REASON_ANR
                } ?: return
            val prefs = context.getSharedPreferences("NuvioNativeTrace", Context.MODE_PRIVATE)
            if (prefs.getLong("reportedExitTime", 0L) >= previous.timestamp) return

            val reason = if (previous.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
                "CRASH_NATIVE" else "ANR"
            Log.d(TAG, "previousExit=$reason pid=${previous.pid} timestamp=${previous.timestamp}")
            var found = 0
            val trace = previous.traceInputStream
            if (trace != null) {
                trace.bufferedReader().use { reader ->
                    // Bounded, one-time extraction. Never retain full tombstones in memory.
                    repeat(350) {
                        val line = reader.readLine() ?: return@use
                        val item = line.trimStart()
                        if (item.startsWith("#") || item.startsWith("backtrace:") ||
                            item.startsWith("signal ") || item.startsWith("Abort message:") ||
                            item.startsWith("pid:") || item.startsWith("Cmdline:") ||
                            item.contains("JNI ERROR")) {
                            if (found < 35) {
                                Log.d(TAG, "nativeStack " + item.take(240))
                                found++
                            }
                        }
                    }
                }
            }
            if (found == 0) Log.d(TAG, "nativeStack=UNAVAILABLE; capture adb bugreport/tombstone")
            prefs.edit().putLong("reportedExitTime", previous.timestamp).apply()
        } catch (e: Exception) {
            Log.d(TAG, "nativeStack=UNAVAILABLE " + e.javaClass.simpleName)
        }
    }
}
