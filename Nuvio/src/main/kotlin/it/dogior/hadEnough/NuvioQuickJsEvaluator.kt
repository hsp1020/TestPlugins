package it.dogior.hadEnough

import com.dokar.quickjs.quickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
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
    )

    suspend fun evaluateReal(test: RealScraper): String {
        require(test.code.isNotBlank()) { "SCRIPT_MISSING" }
        require(test.code.length <= 2_000_000) { "SCRIPT_TOO_LARGE" }
        require(test.tmdbId.isNotBlank() && test.tmdbId.all { it.isDigit() }) { "INVALID_TMDB_ID" }
        require(test.mediaType == "movie" || test.mediaType == "tv") { "INVALID_MEDIA_TYPE" }
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val jsErrors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val host = NuvioQuickJsHost(onResponse = { url, status, error ->
            val domain = runCatching { URI(url).host }.getOrNull() ?: "unknown"
            requests.add("$domain: ${status ?: error ?: "error"}")
        })
        var stage = "BOOTSTRAP"
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

                    stage = "SCRIPT_COMPILE"
                    val exported = evaluate<String>(
                        "var module = { exports: {} }; var exports = module.exports;\n" +
                            "(function() {\n" + test.code + "\n" +
                            "if (typeof module.exports.getStreams !== 'function' && " +
                            "typeof getStreams === 'function') module.exports.getStreams = getStreams;\n" +
                            "})();\nString(typeof module.exports.getStreams)"
                    )
                    check(exported == "function") { "GETSTREAMS_MISSING: $exported" }

                    stage = "GETSTREAMS"
                    evaluate<String>(
                        """JSON.stringify(await (async function() {
                          var fn = module.exports.getStreams;
                          var result = await fn.call(module.exports, """ +
                            JSONObject.quote(test.tmdbId) + ", " + JSONObject.quote(test.mediaType) + ", " +
                            (test.season?.toString() ?: "null") + ", " +
                            (test.episode?.toString() ?: "null") +
                            """);
                          return Array.isArray(result) ? result :
                            (result && Array.isArray(result.streams) ? result.streams : []);
                        })())"""
                    )
                }
            }
            stage = "RESULT_PARSE"
            check(json.length <= 5_000_000) { "RESULT_TOO_LARGE" }
            val array = JSONArray(json)
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
            return buildString {
                append("REAL: ").append(
                    if (links > 0) "PASS" else if (array.length() == 0) "EMPTY" else "INVALID"
                )
                append(" (links=").append(links).append(", items=").append(array.length()).append(")")
                append("\nHTTP: ").append(requests.size).append(" requests")
                if (hosts.isNotEmpty()) append("\nHOSTS: ").append(hosts)
                if (samples.isNotEmpty()) append("\nSAMPLES: ").append(samples.joinToString("; "))
                if (warnings.isNotEmpty()) append("\nJS WARN/ERROR: ").append(warnings)
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("TIMEOUT: ${test.timeoutMs} ms; HTTP requests=${requests.size}", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            val recent = synchronized(requests) { requests.takeLast(3).joinToString("; ") }
            val warnings = synchronized(jsErrors) { jsErrors.takeLast(3).joinToString("; ") }
            throw IllegalStateException(buildString {
                append(stage).append(": ").append(t.message)
                if (recent.isNotEmpty()) append(" | HTTP: ").append(recent)
                if (warnings.isNotEmpty()) append(" | JS: ").append(warnings)
            }, t)
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
