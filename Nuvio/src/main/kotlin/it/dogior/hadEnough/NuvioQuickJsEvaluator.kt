package it.dogior.hadEnough

import com.dokar.quickjs.quickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.net.URI

/** Separate class keeps QuickJS linkage out of the normal Nuvio plugin load path. */
internal object NuvioQuickJsEvaluator {
    suspend fun evaluate(): String {
        repeat(3) {
            quickJs(Dispatchers.Default) {
                val modern = evaluate<String>(
                    "class Counter { constructor(n) { this.n = n; } add(x) { return this.n + x; } }; " +
                        "const counter = new Counter(40); String(counter.add(2))"
                )
                check(modern == "42") { "class/const mismatch: $modern" }
                val generator = evaluate<String>(
                    "function* sequence() { yield 5; }; String(sequence().next().value)"
                )
                check(generator == "5") { "generator mismatch: $generator" }
                val async = evaluate<String>(
                    "String(await (async function() { return await Promise.resolve(7); })())"
                )
                check(async == "7") { "async/await mismatch: $async" }
            }
        }
        return "class, const, generator, async/await; 3 create/evaluate/close cycles"
    }

    /** P0-1 only: verify the Nuvio getStreams contract without HTTP or actual scrapers. */
    suspend fun evaluateAdapter(): String {
        quickJs(Dispatchers.Default) {
            val export = evaluate<String>(
                """
                var module = { exports: {} };
                var exports = module.exports;
                (function () {
                    module.exports.getStreams = async function (tmdbId, mediaType, season, episode) {
                        await Promise.resolve();
                        return [{
                            name: "QuickJS P0-1",
                            url: "https://example.invalid/p0-1.m3u8",
                            quality: "1080p",
                            request: {
                                tmdbId: tmdbId,
                                mediaType: mediaType,
                                season: season,
                                episode: episode
                            }
                        }];
                    };
                })();
                typeof module.exports.getStreams
                """.trimIndent()
            )
            check(export == "function") { "module.exports.getStreams missing: $export" }

            val movie = evaluate<String>(
                "JSON.stringify(await module.exports.getStreams('550', 'movie', null, null))"
            )
            verifyResult(movie, "550", "movie", null, null)

            val series = evaluate<String>(
                "JSON.stringify(await module.exports.getStreams('1399', 'tv', 2, 3))"
            )
            verifyResult(series, "1399", "tv", 2, 3)
        }
        return "module.exports.getStreams, movie/tv arguments, async JSON; no HTTP"
    }

    /** Offline P0-2 contract test; does not run a repository scraper. */
    suspend fun evaluateHost(): String {
        val calls = AtomicInteger()
        var stage = "init"
        try {
            val host = NuvioQuickJsHost { url, method, headers, body, _ ->
                if (url.endsWith("/error")) error("mock network failure")
                check(url == "https://example.invalid/p0-2") { "Unexpected URL: " + url }
                if (method == "POST") {
                    check(headers["X-Probe"] == "yes" && body == "payload") { "POST args mismatch" }
                } else check(method == "GET") { "Unexpected HTTP method: " + method }
                calls.incrementAndGet()
                val payload = JSONObject().put("accepted", true).put("method", method).toString()
                NuvioHttpResult(if (method == "POST") 201 else 200, "OK", url,
                    mapOf("x-nuvio-test" to "bridge"), payload, payload.toByteArray())
            }
            quickJs(Dispatchers.Default) {
                host.register(this, """{"mode":"p0-2"}""")
                val ready = evaluate<String>(
                    NUVIO_QUICKJS_FETCH_JS + "\n" + NUVIO_QUICKJS_HOST_PRELUDE + "\nString(typeof require)"
                )
                check(ready == "function") { "Module bootstrap failed" }

                stage = "fetch"
                val response = JSONObject(evaluate<String>(
                    """JSON.stringify(await (async function() {
                        var r = await fetch('https://example.invalid/p0-2',
                            {method:'POST', headers:{'X-Probe':'yes'}, body:'payload'});
                        return {ok:r.ok, status:r.status, header:r.headers.get('X-Nuvio-Test'),
                            body:await r.json()};
                    })())"""
                ))
                check(response.getBoolean("ok") && response.getInt("status") == 201 &&
                    response.getString("header") == "bridge" &&
                    response.getJSONObject("body").getString("method") == "POST") {
                    "fetch response mismatch"
                }

                stage = "axios"
                val axios = JSONObject(evaluate<String>(
                    """JSON.stringify((await require('axios').get('https://example.invalid/p0-2')).data)"""
                ))
                check(axios.getBoolean("accepted") && axios.getString("method") == "GET") {
                    "axios response mismatch"
                }

                stage = "DOM"
                val attr = evaluate<String>(
                    """require('cheerio').load("<a class='item' href='/play'>Title</a>")('.item').attr('href')"""
                )
                check(attr == "/play") { "DOM selector mismatch: " + attr }

                stage = "crypto-js"
                val hash = evaluate<String>("require('crypto-js').SHA256('abc').toString()")
                check(hash == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") {
                    "SHA256 mismatch: " + hash
                }

                stage = "settings/require"
                check(evaluate<String>("String(SCRAPER_SETTINGS.mode)") == "p0-2") {
                    "SCRAPER_SETTINGS mismatch"
                }
                val missing = evaluate<String>(
                    """(function(){try{require('unknown-module');return 'missing';}
                    catch(e){return String(e.code);}})()"""
                )
                check(missing == "MODULE_NOT_FOUND") { "require error mismatch: " + missing }

                stage = "HTTP error"
                val errorMessage = evaluate<String>(
                    """String(await (async function(){try{
                      await fetch('https://example.invalid/error');return 'missing';
                    }catch(e){return e.message;}})())"""
                )
                check(errorMessage.contains("mock network failure")) {
                    "Fetch failure not propagated: " + errorMessage
                }
            }
            check(calls.get() == 2) { "Unexpected mock HTTP call count: " + calls.get() }

            stage = "cancellation"
            val started = AtomicBoolean(false)
            val timeout = try {
                quickJs(Dispatchers.Default) {
                    asyncFunction("__p02Wait") { _ ->
                        started.set(true)
                        delay(10_000)
                        "done"
                    }
                    withTimeout(800L) { evaluate<String>("await __p02Wait()") }
                }
                false
            } catch (_: TimeoutCancellationException) {
                true
            }
            check(started.get() && timeout) { "Coroutine cancellation did not interrupt QuickJS" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalStateException("P0-2 " + stage + ": " + t.message, t)
        }
        return "fetch, axios, DOM, crypto-js, require, settings, errors, cancellation; offline"
    }

    /** Test a single installed scraper using actual HTTP without publishing its links. */
    data class RealScraper(
        val name: String,
        val code: String,
        val settingsJson: String,
        val tmdbId: String,
        val mediaType: String,
        val season: Int?,
        val episode: Int?,
        val timeoutMs: Long,
        val retryTimeoutMs: Long = timeoutMs,
    )

    suspend fun evaluateReal(test: RealScraper): String {
        require(test.code.isNotBlank()) { "SCRIPT_MISSING" }
        require(test.code.length <= 2_000_000) { "SCRIPT_TOO_LARGE" }
        require(test.tmdbId.isNotBlank() && test.tmdbId.all { it.isDigit() }) { "INVALID_TMDB_ID" }
        require(test.mediaType == "movie" || test.mediaType == "tv") { "INVALID_MEDIA_TYPE" }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var stage = "BOOTSTRAP"
        var stageSince = startedAt
        val stageTimes = mutableListOf<String>()
        fun nextStage(name: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            stageTimes.add("$stage=" + (now - stageSince) + "ms")
            stageSince = now
            stage = name
        }
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val statusCodes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val httpErrors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val jsErrors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val lastHost = java.util.concurrent.atomic.AtomicReference("none")
        val startedHttp = AtomicInteger()
        fun domain(url: String) = runCatching { URI(url).host }.getOrNull() ?: "unknown"
        val host = NuvioQuickJsHost(
            onRequest = { url -> lastHost.set(domain(url)); startedHttp.incrementAndGet() },
            onResponse = { url, status, error ->
                requests.add(domain(url) + ": " + (status?.toString() ?: error ?: "error"))
                if (status != null) statusCodes.add(status)
                if (error != null) httpErrors.add(error)
            },
        )
        try {
            val json = withTimeout(test.timeoutMs) {
                quickJs(Dispatchers.Default) {
                    host.register(this, test.settingsJson)
                    function("__p03Log") { args ->
                        val level = args.getOrNull(0)?.toString().orEmpty()
                        if (level == "error" || level == "warn") {
                            if (jsErrors.size < 5) {
                                jsErrors.add("$level: " + args.getOrNull(1)?.toString().orEmpty().take(160))
                            }
                        }
                        null
                    }
                    var captured: String? = null
                    function("__p03Capture") { args ->
                        captured = args.getOrNull(0)?.toString()
                        null
                    }
                    val bootstrap = evaluate<String>(
                        NUVIO_QUICKJS_FETCH_JS + "\n" + NUVIO_QUICKJS_HOST_PRELUDE + "\n" +
                            """
                            globalThis.console = {
                              log: function(){}, info: function(){}, debug: function(){},
                              warn: function(){ __p03Log('warn', Array.from(arguments).join(' ')); },
                              error: function(){ __p03Log('error', Array.from(arguments).join(' ')); }
                            };
                            globalThis.atob = function(s) { return __latin1Decode(__b64ToBytes(s)); };
                            globalThis.btoa = function(s) { return __bytesToB64(__latin1Bytes(s)); };
                            String(typeof fetch)
                            """.trimIndent()
                    )
                    check(bootstrap == "function") { "FETCH_BRIDGE_MISSING" }

                    nextStage("SCRIPT_COMPILE")
                    val exported = evaluate<String>(
                        "var module = { exports: {} }; var exports = module.exports;\n" +
                            "(function() {\n" + test.code + "\n" +
                            "if (typeof module.exports.getStreams !== 'function' && " +
                            "typeof getStreams === 'function') module.exports.getStreams = getStreams;\n" +
                            "})();\nString(typeof module.exports.getStreams)"
                    )
                    check(exported == "function") { "GETSTREAMS_MISSING: $exported" }

                    nextStage("GETSTREAMS")
                    // Receive the settled async result via callback, as in official NuvioTV.
                    evaluate<Any?>(
                        """(async function() {
                          try {
                            var fn = module.exports.getStreams;
                            var result = await fn.call(module.exports, """ +
                            JSONObject.quote(test.tmdbId) + ", " + JSONObject.quote(test.mediaType) + ", " +
                            (test.season?.toString() ?: "null") + ", " +
                            (test.episode?.toString() ?: "null") +
                            """);
                            if (result && typeof result.then === 'function') result = await result;
                            var streams = Array.isArray(result) ? result :
                              (result && Array.isArray(result.streams) ? result.streams : []);
                            __p03Capture(JSON.stringify({ok:true,streams:streams}));
                          } catch(e) {
                            __p03Capture(JSON.stringify({ok:false,error:String(e && (e.message || e))}));
                          }
                        })();"""
                    )
                    captured ?: error("ASYNC_UNSETTLED: completion callback not invoked")
                }
            }
            nextStage("RESULT_PARSE")
            check(json.length <= 5_000_000) { "RESULT_TOO_LARGE" }
            val payload = JSONObject(json)
            if (!payload.optBoolean("ok")) {
                throw IllegalStateException("JS_ERROR: " + payload.optString("error", "unknown").take(280))
            }
            val array = payload.getJSONArray("streams")
            var links = 0
            val samples = ArrayList<String>(3)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("url").isNotBlank() || item.optString("infoHash").isNotBlank()) {
                    links++
                    if (samples.size < 3) {
                        samples.add(item.optString("name", "Stream").take(64) + " / " +
                            item.optString("quality", "Auto").take(20))
                    }
                }
            }
            val hosts = synchronized(requests) { requests.take(8).joinToString("; ") }
            val warnings = synchronized(jsErrors) { jsErrors.take(5).joinToString("; ") }
            val statuses = synchronized(statusCodes) { statusCodes.toList() }
            val errors = synchronized(httpErrors) { httpErrors.joinToString(" ") }
            val emptyReason = when {
                warnings.contains("token", true) || warnings.contains("cookie", true) ||
                    warnings.contains("configure", true) -> "AUTH_REQUIRED"
                statuses.any { it == 401 || it == 403 || it == 429 } -> "HTTP_BLOCKED"
                errors.contains("UnknownHost") || errors.contains("SSL") -> "NETWORK_ERROR"
                statuses.any { it >= 400 } -> "UPSTREAM_ERROR"
                else -> "NO_MATCH_OR_PARSE"
            }
            nextStage("DONE")
            return buildString {
                append("REAL: ").append(
                    if (links > 0) "PASS" else if (array.length() == 0) "EMPTY" else "INVALID"
                )
                append(" (links=").append(links).append(", items=").append(array.length()).append(")")
                if (links == 0 && array.length() == 0) append(" reason=").append(emptyReason)
                append("\nTIME: ").append(android.os.SystemClock.elapsedRealtime() - startedAt)
                    .append("ms (").append(stageTimes.joinToString(", ")).append(")")
                append("\nHTTP: ").append(startedHttp.get()).append(" started, ")
                    .append(requests.size).append(" completed")
                if (hosts.isNotEmpty()) append("\nHOSTS: ").append(hosts)
                if (samples.isNotEmpty()) append("\nSAMPLES: ").append(samples.joinToString("; "))
                if (warnings.isNotEmpty()) append("\nJS WARN/ERROR: ").append(warnings)
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(
                "TIMEOUT: " + test.timeoutMs + "ms stage=" + stage +
                    " stageMs=" + (android.os.SystemClock.elapsedRealtime() - stageSince) +
                    " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - startedAt) +
                    " timings=" + stageTimes.joinToString(",") +
                    " http=" + requests.size + "/" + startedHttp.get() +
                    " lastHost=" + lastHost.get(), e
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            val recent = synchronized(requests) { requests.takeLast(3).joinToString("; ") }
            val warnings = synchronized(jsErrors) { jsErrors.takeLast(3).joinToString("; ") }
            throw IllegalStateException(buildString {
                append(stage).append(": ").append(t.message)
                append(" | elapsedMs=").append(android.os.SystemClock.elapsedRealtime() - startedAt)
                append(" | timings=").append(stageTimes.joinToString(","))
                if (recent.isNotEmpty()) append(" | HTTP: ").append(recent)
                if (warnings.isNotEmpty()) append(" | JS: ").append(warnings)
            }, t)
        }
    }

    /** One batch entry per scraper, with a conditional alternative media type. */
    suspend fun evaluateBatch(
        tests: List<List<RealScraper>>,
        onProgress: suspend (Int, Int, String) -> Unit,
    ): String {
        val slots = kotlinx.coroutines.sync.Semaphore(10)
        val finished = AtomicInteger()
        val reports = Array(tests.size) { "" }
        val categories = Array(tests.size) { "FAIL" }
        suspend fun run(test: RealScraper): String {
            suspend fun attempt(item: RealScraper): String = try {
                evaluateReal(item)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Exception) {
                "REAL: FAIL: " + t.javaClass.simpleName + ": " + t.message.orEmpty().take(400)
            }
            val initial = attempt(test)
            if (!initial.contains("TIMEOUT:") || test.retryTimeoutMs <= test.timeoutMs) return initial
            val retried = attempt(test.copy(timeoutMs = test.retryTimeoutMs))
            return initial + "\nRETRY (" + test.retryTimeoutMs + "ms): " + retried
        }
        coroutineScope {
            tests.mapIndexed { index, variants ->
                async(Dispatchers.Default) {
                    slots.withPermit {
                        val first = run(variants[0])
                        val outcomes = ArrayList<String>(2)
                        outcomes.add(variants[0].mediaType + " TMDB " + variants[0].tmdbId + ": " + first)
                        if (!first.contains("REAL: PASS") && variants.size > 1 &&
                            first.contains("REAL: EMPTY") && first.contains("reason=NO_MATCH_OR_PARSE")) {
                            val alternative = run(variants[1])
                            outcomes.add(variants[1].mediaType + " TMDB " +
                                variants[1].tmdbId + ": " + alternative)
                        }
                        val details = outcomes.joinToString("\n")
                        reports[index] = details
                        categories[index] = when {
                            details.contains("REAL: PASS") -> "PASS"
                            details.contains("REAL: INVALID") -> "INVALID"
                            details.contains("REAL: FAIL") -> "FAIL"
                            else -> "EMPTY"
                        }
                        onProgress(finished.incrementAndGet(), tests.size, variants[0].name)
                    }
                }
            }.awaitAll()
        }
        val redactUrls = Regex("""https?://\S+""")
        return buildString {
            append("BATCH: COMPLETE (").append(tests.size).append(" scrapers)")
            append("\nPASS=").append(categories.count { it == "PASS" })
            append(" EMPTY=").append(categories.count { it == "EMPTY" })
            append(" INVALID=").append(categories.count { it == "INVALID" })
            append(" FAIL=").append(categories.count { it == "FAIL" })
            tests.forEachIndexed { index, variants ->
                append("\n\n").append(index + 1).append(". ").append(variants[0].name)
                append(" [").append(categories[index]).append("]\n")
                append(redactUrls.replace(reports[index], "[URL]"))
            }
        }
    }

    private fun verifyResult(json: String, id: String, type: String, season: Int?, episode: Int?) {
        val streams = JSONArray(json)
        check(streams.length() == 1) { "Expected one stream for $type, got ${streams.length()}" }
        val stream = streams.getJSONObject(0)
        check(stream.getString("name") == "QuickJS P0-1") { "Invalid stream name" }
        check(stream.getString("url") == "https://example.invalid/p0-1.m3u8") { "Invalid stream URL" }
        check(stream.getString("quality") == "1080p") { "Invalid quality" }
        val request = stream.getJSONObject("request")
        check(request.getString("tmdbId") == id) { "Wrong TMDB ID for $type" }
        check(request.getString("mediaType") == type) { "Wrong media type" }
        check(if (season == null) request.isNull("season") else request.getInt("season") == season) {
            "Wrong season for $type"
        }
        check(if (episode == null) request.isNull("episode") else request.getInt("episode") == episode) {
            "Wrong episode for $type"
        }
    }
}
