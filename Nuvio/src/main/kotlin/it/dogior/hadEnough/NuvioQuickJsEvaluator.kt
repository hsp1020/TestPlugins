package it.dogior.hadEnough

import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray

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
