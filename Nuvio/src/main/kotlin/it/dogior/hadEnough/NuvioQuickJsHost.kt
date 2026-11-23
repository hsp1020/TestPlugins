package it.dogior.hadEnough

import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** P0-2 QuickJS host bindings. Tests inject a deterministic transport. */
internal class NuvioQuickJsHost(
    private val onRequest: ((Long, String, Long) -> Unit)? = null,
    private val onResponse: ((Long, String, Int?, String?, Long) -> Unit)? = null,
    private val request: suspend (String, String, Map<String, String>, String?, Boolean) -> NuvioHttpResult =
        { url, method, headers, body, redirects ->
            withContext(Dispatchers.IO) {
                NuvioHttp.execute(app.baseClient, url, method, headers, body, redirects)
            }
        },
) {
    private val requestIds = AtomicLong()

    fun register(qjs: QuickJs, settingsJson: String) {
        val dom = NuvioDom()
        qjs.function("__get_scraper_settings") { settingsJson }
        qjs.function("__domCall") { args ->
            dom.call(args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString() ?: "[]",
                args.getOrNull(2)?.toString(), args.getOrNull(3)?.toString())
        }
        qjs.function("__cryptoHash") { a ->
            NuvioCrypto.hash(a.getOrNull(0)?.toString().orEmpty(), a.getOrNull(1)?.toString().orEmpty())
        }
        qjs.function("__cryptoHmac") { a ->
            NuvioCrypto.hmac(a.getOrNull(0)?.toString().orEmpty(),
                a.getOrNull(1)?.toString().orEmpty(), a.getOrNull(2)?.toString().orEmpty())
        }
        qjs.function("__cryptoCipher") { a ->
            NuvioCrypto.cipher(a.getOrNull(0)?.toString().orEmpty(), a.getOrNull(1)?.toString().orEmpty(),
                a.getOrNull(2)?.toString().orEmpty(), a.getOrNull(3)?.toString().orEmpty(),
                a.getOrNull(4)?.toString().orEmpty(), a.getOrNull(5)?.toString().orEmpty(),
                a.getOrNull(6)?.toString().orEmpty())
        }
        qjs.function("__cryptoRandom") { a ->
            NuvioCrypto.random((a.getOrNull(0) as? Number)?.toInt() ?: 0)
        }
        qjs.asyncFunction("__nuvioNativeFetch") { a ->
            val url = a.getOrNull(0)?.toString().orEmpty()
            val method = a.getOrNull(1)?.toString() ?: "GET"
            val rawHeaders = JSONObject(a.getOrNull(2)?.toString() ?: "{}")
            val headers = LinkedHashMap<String, String>()
            for (key in rawHeaders.keys()) headers[key] = rawHeaders.optString(key)
            val id = requestIds.incrementAndGet()
            val startedAt = android.os.SystemClock.elapsedRealtime()
            onRequest?.invoke(id, url, startedAt)
            val result = try {
                request(url, method, headers, a.getOrNull(3)?.toString(),
                    a.getOrNull(4)?.toString() != "manual")
            } catch (e: kotlinx.coroutines.CancellationException) {
                onResponse?.invoke(id, url, null, "CANCELLED",
                    android.os.SystemClock.elapsedRealtime() - startedAt)
                throw e
            } catch (t: Throwable) {
                onResponse?.invoke(id, url, null, t.javaClass.simpleName,
                    android.os.SystemClock.elapsedRealtime() - startedAt)
                throw t
            }
            onResponse?.invoke(id, url, result.status, null,
                android.os.SystemClock.elapsedRealtime() - startedAt)
            JSONObject().apply {
                put("status", result.status)
                put("statusText", result.statusText)
                put("url", result.url)
                put("headers", JSONObject(result.headers))
                put("body", result.text)
                put("bodyBase64", Base64.encodeToString(result.bytes, Base64.NO_WRAP))
            }.toString()
        }
        val random = SecureRandom()
        qjs.function("__nuvioRandomBytes") { args ->
            val count = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            require(count in 0..65_536) { "Invalid random byte length" }
            val bytes = ByteArray(count)
            random.nextBytes(bytes)
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        qjs.asyncFunction("__nuvioSleep") { a ->
            delay(((a.getOrNull(0) as? Number)?.toLong() ?: 0L).coerceIn(0L, 60_000L))
            null
        }
    }
}

/** Minimal Fetch response adapter; URL/axios/cheerio/crypto-js come from shared Nuvio modules. */
internal const val NUVIO_QUICKJS_FETCH_JS = """
globalThis.SCRAPER_SETTINGS = JSON.parse(__get_scraper_settings());
globalThis.window = globalThis;
globalThis.self = globalThis;
globalThis.global = globalThis;
if (!globalThis.crypto) globalThis.crypto = {};
if (typeof globalThis.crypto.getRandomValues !== 'function') {
  globalThis.crypto.getRandomValues = function(bytes) {
    if (!bytes || !ArrayBuffer.isView(bytes) || bytes.byteLength > 65536)
      throw new TypeError('Invalid random buffer');
    var raw = __b64ToBytes(__nuvioRandomBytes(bytes.byteLength));
    new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength).set(raw);
    return bytes;
  };
}
globalThis.setTimeout = function(fn, ms) {
  var token = { cancelled: false };
  __nuvioSleep(ms).then(function() { if (!token.cancelled) fn(); });
  return token;
};
globalThis.clearTimeout = function(token) { if (token) token.cancelled = true; };
globalThis.fetch = async function(input, init) {
  init = init || {};
  var url = typeof input === 'string' ? input : String(input.url);
  var original = init.headers || {}, headers = {};
  if (Array.isArray(original)) original.forEach(function(p) { headers[p[0]] = String(p[1]); });
  else if (typeof original.forEach === 'function')
    original.forEach(function(v, k) { headers[k] = String(v); });
  else Object.keys(original).forEach(function(k) { headers[k] = String(original[k]); });
  var raw = await __nuvioNativeFetch(url, String(init.method || 'GET').toUpperCase(),
    JSON.stringify(headers), init.body == null ? null : String(init.body),
    String(init.redirect || 'follow'));
  var result = JSON.parse(raw), lower = {};
  Object.keys(result.headers || {}).forEach(function(k) {
    lower[k.toLowerCase()] = result.headers[k];
  });
  return {
    ok: result.status >= 200 && result.status < 300,
    status: result.status, statusText: result.statusText, url: result.url,
    headers: {
      get: function(k) { k = String(k).toLowerCase(); return k in lower ? lower[k] : null; },
      has: function(k) { return String(k).toLowerCase() in lower; },
      forEach: function(cb) { Object.keys(lower).forEach(function(k) { cb(lower[k], k); }); }
    },
    text: async function() { return result.body; },
    json: async function() { return JSON.parse(result.body); },
    arrayBuffer: async function() { return new Uint8Array(__b64ToBytes(result.bodyBase64)).buffer; }
  };
};
""";
