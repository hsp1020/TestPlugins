package com.hsp1020

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.lagradost.api.Log
import com.dokar.quickjs.quickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** QuickJS production runtime; Rhino remains available for engine errors or manual selection. */
internal object NuvioQuickJsEvaluator {
    private const val TAG = "NuvioQuickJS"
    data class Result(val json: String?, val error: String?, val fallbackToRhino: Boolean = false)

    @Volatile private var context: Context? = null
    @Volatile private var archive: String? = null
    @Volatile private var nativeLoaded = false
    private val nativeLock = Any()
    private const val LIB = "libquickjs.so"
    private const val PATH_KEY = "com.dokar.quickjs.library.path"
    private const val NAME_KEY = "com.dokar.quickjs.library.name"

    private val bootstrap by lazy {
        NUVIO_QUICKJS_FETCH_JS + "\n" + NUVIO_QUICKJS_HOST_PRELUDE + "\n" +
            """
            globalThis.console = {
              log: function(){}, info: function(){}, debug: function(){},
              warn: function(){ __nuvioConsole('warn', Array.from(arguments).join(' ')); },
              error: function(){ __nuvioConsole('error', Array.from(arguments).join(' ')); }
            };
            globalThis.atob = function(s) { return __latin1Decode(__b64ToBytes(s)); };
            globalThis.btoa = function(s) { return __bytesToB64(__latin1Bytes(s)); };
            """.trimIndent()
    }

    fun configure(appContext: Context, pluginFilePath: String?) {
        context = appContext.applicationContext
        archive = pluginFilePath
    }

    private fun ensureNative() {
        if (nativeLoaded) return
        synchronized(nativeLock) {
            if (nativeLoaded) return
            val appContext = requireNotNull(context) { "Nuvio QuickJS is not configured" }
            val cs3 = File(requireNotNull(archive) { "Nuvio plugin archive path is missing" })
            require(cs3.isFile) { "Nuvio plugin archive is missing" }
            val nativeFile = ZipFile(cs3).use { zip ->
                val abi = Build.SUPPORTED_ABIS.firstOrNull {
                    zip.getEntry("lib/${it}/$LIB") != null
                } ?: error("QuickJS native ABI is not available")
                val entry = requireNotNull(zip.getEntry("lib/${abi}/$LIB"))
                val folder = File(appContext.codeCacheDir,
                    "nuvio-quickjs/${abi}/${entry.crc.toString(16)}")
                check(folder.isDirectory || folder.mkdirs()) { "Cannot create native cache" }
                val target = File(folder, LIB)
                if (!target.isFile || target.length() != entry.size) {
                    val temp = File(folder, "$LIB.tmp")
                    try {
                        zip.getInputStream(entry).use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                        check(temp.setReadOnly()) { "Cannot mark QuickJS native library read-only" }
                        if (target.exists()) check(target.delete()) { "Cannot replace native library" }
                        check(temp.renameTo(target)) { "Cannot stage native library" }
                    } finally {
                        temp.delete()
                    }
                }
                target
            }
            val oldPath = System.getProperty(PATH_KEY)
            val oldName = System.getProperty(NAME_KEY)
            try {
                System.setProperty(PATH_KEY, nativeFile.parentFile!!.absolutePath)
                System.setProperty(NAME_KEY, LIB)
                // QuickJS 1.0.5 loads JNI in its companion initializer.
                Class.forName("com.dokar.quickjs.QuickJs", true,
                    NuvioQuickJsEvaluator::class.java.classLoader)
                nativeLoaded = true
            } finally {
                if (oldPath == null) System.clearProperty(PATH_KEY) else System.setProperty(PATH_KEY, oldPath)
                if (oldName == null) System.clearProperty(NAME_KEY) else System.setProperty(NAME_KEY, oldName)
            }
        }
    }

    suspend fun run(
        scraperName: String, code: String, settingsJson: String, tmdbId: String,
        mediaType: String, season: Int?, episode: Int?, timeoutMs: Long,
    ): Result {
        try {
            require(code.isNotBlank()) { "Scraper JS is empty" }
            ensureNative()
            val host = NuvioQuickJsHost()
            var captured: String? = null
            var authMissing = false
            val started = SystemClock.elapsedRealtime()
            withTimeout(timeoutMs) {
                quickJs(Dispatchers.Default) {
                    host.register(this, settingsJson)
                    function("__nuvioConsole") { args ->
                        val message = args.getOrNull(1)?.toString().orEmpty()
                        if (args.getOrNull(0) == "error" || args.getOrNull(0) == "warn") {
                            if (listOf("no token", "no ui token", "missing token",
                                    "no cookie", "missing cookie", "cookie not found")
                                    .any { message.contains(it, true) }) authMissing = true
                            Log.d(TAG, "[$scraperName] " + message.take(240))
                        }
                        null
                    }
                    function("__nuvioCapture") { args ->
                        captured = args.getOrNull(0)?.toString()
                        null
                    }
                    evaluate<Any?>(bootstrap)
                    val exported = evaluate<String>(
                        "var module = { exports: {} }; var exports = module.exports;\n" +
                            "(function() {\n" + code + "\n" +
                            "if (typeof module.exports.getStreams !== 'function' && " +
                            "typeof getStreams === 'function') module.exports.getStreams = getStreams;\n" +
                            "})();\nString(typeof module.exports.getStreams)"
                    )
                    check(exported == "function") { "getStreams export is missing" }
                    evaluate<Any?>(
                        """(async function() {
                            try {
                                var streams = await module.exports.getStreams(""" +
                            JSONObject.quote(tmdbId) + ", " + JSONObject.quote(mediaType) + ", " +
                            (season?.toString() ?: "null") + ", " + (episode?.toString() ?: "null") +
                            """);
                                if (streams && typeof streams.then === 'function') streams = await streams;
                                if (!Array.isArray(streams))
                                    streams = streams && Array.isArray(streams.streams) ? streams.streams : [];
                                __nuvioCapture(JSON.stringify({ok:true, streams:streams}));
                            } catch(e) {
                                __nuvioCapture(JSON.stringify({ok:false,error:String(e && (e.message || e))}));
                            }
                        })();"""
                    )
                }
            }
            // Check after the async QuickJS evaluation scope has completed.
            val response = JSONObject(captured ?: error("QuickJS result was not captured"))
            if (!response.optBoolean("ok")) {
                return Result(null, response.optString("error", "JavaScript error"), true)
            }
            val streams = response.optJSONArray("streams") ?: JSONArray()
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= 15_000) Log.d(TAG, "[$scraperName] QuickJS took ${elapsed}ms")
            return Result(streams.toString(),
                if (streams.length() == 0 && authMissing) "AUTH_REQUIRED" else null)
        } catch (e: TimeoutCancellationException) {
            return Result(null, "timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            return Result(null, t.message ?: t.javaClass.simpleName, true)
        }
    }
}
