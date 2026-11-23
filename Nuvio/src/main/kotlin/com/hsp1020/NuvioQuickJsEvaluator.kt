package com.hsp1020

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.lagradost.api.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/** QuickJS-only runtime for scrapers and settings. */
internal object NuvioQuickJsEvaluator {
    private const val TAG = "NuvioQuickJS"
    data class Result(val json: String?, val error: String?, val streams: Int = 0, val elapsedMs: Long = 0L)

    @Volatile private var context: Context? = null
    @Volatile private var archive: String? = null
    @Volatile private var nativeLoaded = false
    @Volatile private var nativeFailure: String? = null
    private val nativeLock = Any()
    private val nextRuntime = AtomicLong()
    private val nativeScope = UUID.randomUUID().toString()
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

    /** A failed initialization is recorded once for this plugin ClassLoader. */
    fun prepare(): String? {
        if (nativeLoaded) return null
        nativeFailure?.let { return it }
        synchronized(nativeLock) {
            if (nativeLoaded) return null
            nativeFailure?.let { return it }
            try {
                loadNative()
                nativeLoaded = true
            } catch (t: Throwable) {
                if (t is VirtualMachineError || t is ThreadDeath) throw t
                val reason = t.javaClass.simpleName + ": " + (t.message ?: "native init failed")
                nativeFailure = reason
                Log.d(TAG, "engine=QUICKJS native=ERROR " + reason.take(240))
            }
            return nativeFailure
        }
    }

    private fun loadNative() {
        val appContext = requireNotNull(context) { "Nuvio QuickJS is not configured" }
        val cs3 = File(requireNotNull(archive) { "Nuvio plugin archive path is missing" })
        require(cs3.isFile) { "Nuvio plugin archive is missing" }
        val nativeFile = ZipFile(cs3).use { zip ->
            val abi = Build.SUPPORTED_ABIS.firstOrNull {
                zip.getEntry("lib/${it}/$LIB") != null
            } ?: error("QuickJS native ABI is not available")
            val entry = requireNotNull(zip.getEntry("lib/${abi}/$LIB"))
            // Each plugin ClassLoader must load its own absolute .so path.
            val folder = File(appContext.codeCacheDir,
                "nuvio-quickjs/${abi}/${entry.crc.toString(16)}/$nativeScope")
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
        // QuickJS 1.0.5 companion initializer reads process-wide properties.
        // Serialize property/load/restore across plugin ClassLoaders.
        synchronized(System.getProperties()) {
            val oldPath = System.getProperty(PATH_KEY)
            val oldName = System.getProperty(NAME_KEY)
            try {
                System.setProperty(PATH_KEY, nativeFile.parentFile!!.absolutePath)
                System.setProperty(NAME_KEY, LIB)
                Class.forName("com.dokar.quickjs.QuickJs", true,
                    NuvioQuickJsEvaluator::class.java.classLoader)
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
        val args = JSONArray().put(tmdbId).put(mediaType)
            .put(season ?: JSONObject.NULL).put(episode ?: JSONObject.NULL)
        return execute(scraperName, code, settingsJson, "getStreams", args.toString(), timeoutMs)
    }

    /** Run the existing scraper-provided settings form entirely through QuickJS. */
    suspend fun settingsBlueprint(scraperName: String, code: String, settingsJson: String): Result =
        execute(scraperName, code, settingsJson, "onSettings", "[]", 20_000L)

    private suspend fun execute(
        scraperName: String, code: String, settingsJson: String,
        method: String, argsJson: String, timeoutMs: Long,
    ): Result {
        val started = SystemClock.elapsedRealtime()
        var nativeEntered = false
        val initError = prepare()
        if (initError != null) return Result(null, initError,
            elapsedMs = SystemClock.elapsedRealtime() - started)
        try {
            require(code.isNotBlank()) { "Scraper JS is empty" }
            val runtimeId = nextRuntime.incrementAndGet()
            val host = NuvioQuickJsHost(scraperName, runtimeId)
            var captured: String? = null
            var captureCalls = 0
            var jsStage = "NOT_STARTED"
            var jsError: String? = null
            var authMissing = false
            nativeEntered = true
            withTimeout(timeoutMs) {
                    Log.d(TAG, "runtime=$runtimeId scraper=$scraperName phase=CREATE")
                    val qjs = QuickJs.create(Dispatchers.Default)
                    Log.d(TAG, "runtime=$runtimeId scraper=$scraperName phase=CREATED")
                    try {
                        host.register(qjs, settingsJson)
                        qjs.function("__nuvioConsole") { args ->
                            val message = args.getOrNull(1)?.toString().orEmpty()
                            if (args.getOrNull(0) == "error" || args.getOrNull(0) == "warn") {
                                if (listOf("no token", "no ui token", "missing token",
                                        "no cookie", "missing cookie", "cookie not found")
                                        .any { message.contains(it, true) }) authMissing = true
                                Log.d(TAG, "[$scraperName] " + message.take(240))
                            }
                            null
                        }
                        qjs.function("__nuvioCapture") { args ->
                            captureCalls++
                            captured = args.getOrNull(0)?.toString()
                            jsStage = "CAPTURED"
                            null
                        }
                        // Record only state; write a diagnostic line if capture fails.
                        qjs.function("__nuvioStage") { args ->
                            jsStage = args.getOrNull(0)?.toString() ?: "UNKNOWN"
                            if (jsStage == "JS_ERROR") {
                                jsError = args.getOrNull(1)?.toString()?.take(180)
                            }
                            null
                        }
                        qjs.evaluate<Any?>(bootstrap)
                        val exported = qjs.evaluate<String>(
                            "var module = { exports: {} }; var exports = module.exports;\n" +
                                "(function() {\n" + code + "\n" +
                                "if (typeof module.exports.$method !== 'function' && " +
                                "typeof $method === 'function') module.exports.$method = $method;\n" +
                                "})();\nString(typeof module.exports.$method)"
                        )
                        check(exported == "function") { "$method export is missing" }
                        val normalize = if (method == "getStreams")
                            "Array.isArray(value) ? value : (value && Array.isArray(value.streams) ? value.streams : [])"
                        else "value === undefined ? null : value"
                        val evaluation = qjs.evaluate<Any?>(
                            """(async function() {
                            try {
                                __nuvioStage("STARTED");
                                var pending = module.exports[""" + JSONObject.quote(method) +
                            """].apply(module.exports, """ + argsJson +
                            """);
                                __nuvioStage(pending && typeof pending.then === "function" ? "PROMISE" : "SYNC");
                                var value = await pending;
                                __nuvioStage("SETTLED");
                                var output = """ + normalize +
                            """;
                                __nuvioCapture(JSON.stringify({ok:true,data:output}));
                            } catch(e) {
                                __nuvioStage("JS_ERROR", String(e && (e.message || e)));
                                __nuvioCapture(JSON.stringify({ok:false,error:String(e && (e.message || e))}));
                            }
                        })();"""
                        )
                        if (captured == null) {
                            Log.d(TAG, "runtime=$runtimeId scraper=$scraperName method=$method " +
                                "phase=CAPTURE_MISSING jsStage=$jsStage captureCalls=$captureCalls " +
                                "evalType=${evaluation?.javaClass?.simpleName ?: "null"} " +
                                "jsError=${jsError ?: "none"}")
                        }
                    } finally {
                        Log.d(TAG, "runtime=$runtimeId scraper=$scraperName phase=CLOSE_BEGIN")
                        qjs.close()
                        Log.d(TAG, "runtime=$runtimeId scraper=$scraperName phase=CLOSED")
                    }
            }
            // Check after the async QuickJS evaluation scope has completed.
            val response = JSONObject(captured ?: error("QuickJS result was not captured"))
            if (!response.optBoolean("ok")) {
                return Result(null, response.optString("error", "JavaScript error"),
                    elapsedMs = SystemClock.elapsedRealtime() - started)
            }
            val value = response.opt("data")
            val json = when (value) {
                is JSONObject, is JSONArray -> value.toString()
                is String -> JSONObject.quote(value)
                null, JSONObject.NULL -> "null"
                else -> value.toString()
            }
            val count = if (method == "getStreams") (value as? JSONArray)?.length() ?: 0 else 0
            return Result(json,
                if (method == "getStreams" && count == 0 && authMissing) "AUTH_REQUIRED" else null,
                count, SystemClock.elapsedRealtime() - started)
        } catch (e: TimeoutCancellationException) {
            return Result(null, "TIMEOUT",
                elapsedMs = SystemClock.elapsedRealtime() - started)
        } catch (e: CancellationException) {
            Log.d(TAG, "[$scraperName] engine=QUICKJS status=CANCELLED nativeEntered=$nativeEntered")
            throw e
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            return Result(null, t.javaClass.simpleName + ": " + (t.message ?: "unknown"),
                elapsedMs = SystemClock.elapsedRealtime() - started)
        }
    }
}
