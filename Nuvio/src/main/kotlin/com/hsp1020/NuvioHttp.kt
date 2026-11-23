package com.hsp1020

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal class NuvioHttpResult(
    val status: Int,
    val statusText: String,
    val url: String,
    val headers: Map<String, String>,
    val text: String,
    val bytes: ByteArray,
)

internal object NuvioHttp {
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun call(
        base: OkHttpClient, url: String, method: String, headers: Map<String, String>,
        body: String?, followRedirects: Boolean,
    ): okhttp3.Call {
        val client = base.newBuilder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
        val builder = Request.Builder().url(url)
        var hasUa = false
        var contentType: String? = null
        for ((k, v) in headers) {
            if (k.equals("user-agent", true)) hasUa = true
            if (k.equals("content-type", true)) contentType = v
            if (k.equals("content-length", true) || k.equals("host", true)) continue
            try { builder.header(k, v) } catch (_: IllegalArgumentException) {}
        }
        if (!hasUa) builder.header("User-Agent", DEFAULT_UA)
        val m = method.uppercase()
        val requestBody = if (m == "GET" || m == "HEAD") null
            else (body ?: "").toRequestBody(contentType?.toMediaTypeOrNull())
        builder.method(m, requestBody)
        return client.newCall(builder.build())
    }

    private fun decode(resp: okhttp3.Response): NuvioHttpResult {
        val bodyObj = resp.body
        val charset = bodyObj?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val bytes = bodyObj?.bytes() ?: ByteArray(0)
        val hdrs = LinkedHashMap<String, String>()
        for (name in resp.headers.names()) {
            hdrs[name.lowercase()] = resp.headers.values(name).joinToString(", ")
        }
        return NuvioHttpResult(resp.code, resp.message, resp.request.url.toString(),
            hdrs, String(bytes, charset), bytes)
    }

    /** QuickJS HTTP bridge: suspend without a blocking worker and cancel the OkHttp call. */
    suspend fun executeAsync(
        base: OkHttpClient, url: String, method: String, headers: Map<String, String>,
        body: String?, followRedirects: Boolean,
    ): NuvioHttpResult = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val activeCall = call(base, url, method, headers, body, followRedirects)
        continuation.invokeOnCancellation { activeCall.cancel() }
        activeCall.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    response.use {
                        val decoded = decode(it)
                        if (continuation.isActive) continuation.resumeWith(Result.success(decoded))
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(t))
                }
            }
        })
    }
}

