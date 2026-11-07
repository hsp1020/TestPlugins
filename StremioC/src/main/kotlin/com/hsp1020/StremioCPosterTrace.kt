package com.hsp1020

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import coil3.EventListener as CoilEventListener
import coil3.SingletonImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.size.SizeResolver
import coil3.transition.Transition
import java.lang.reflect.Field
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener as HttpEventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Temporary browser-only diagnostics. Installs listeners on the already running Coil/OkHttp
 * clients without replacing the native ImageLoader, Call.Factory, requests, or caches.
 * Reflection is intentionally fail-open and the original listener factories are restored on close.
 */
internal object BrowserPosterTrace {
    private data class Slot(
        val target: Any,
        val field: Field,
        val previous: Any,
        val wrapper: Any
    )

    private val watched = ConcurrentHashMap.newKeySet<String>()
    private val ids = AtomicLong(0L)
    private var coilSlot: Slot? = null
    private val httpSlots = ArrayList<Slot>()
    @Volatile private var active = false

    fun watch(cards: List<com.lagradost.cloudstream3.SearchResponse>) {
        if (!active) return
        for (card in cards) card.posterUrl?.takeIf { it.isNotBlank() }?.let(watched::add)
    }

    fun clearCards() {
        watched.clear()
    }

    @Synchronized
    fun start(context: Context) {
        if (active) return
        active = true
        watched.clear()
        try {
            val loader = SingletonImageLoader.get(context.applicationContext)
            val optionsField = loader.javaClass.getDeclaredField("options").apply { isAccessible = true }
            val options = optionsField.get(loader)
            val field = options.javaClass.getDeclaredField("eventListenerFactory").apply { isAccessible = true }
            val previous = field.get(options) as CoilEventListener.Factory
            val wrapper = CoilEventListener.Factory { request ->
                val original = previous.create(request)
                if (active && watched.contains(request.data.toString())) CoilStages(original, request) else original
            }
            field.set(options, wrapper)
            check(field.get(options) === wrapper)
            coilSlot = Slot(options, field, previous, wrapper)
            log("COIL_HOOK", "installed=true loader=${loader.javaClass.simpleName}")
            installHttp(loader)
        } catch (error: Throwable) {
            log("COIL_HOOK", "installed=false reason=${error.javaClass.simpleName}")
        }
    }

    private fun installHttp(loader: coil3.ImageLoader) {
        var found = 0
        for ((factory, _) in loader.components.fetcherFactories) {
            if (!factory.javaClass.name.contains("NetworkFetcher")) continue
            try {
                val lazyField = factory.javaClass.getDeclaredField("networkClientLazy")
                    .apply { isAccessible = true }
                val networkClient = (lazyField.get(factory) as Lazy<*>).value ?: continue
                val clientField = networkClient.javaClass.getDeclaredField("callFactory")
                    .apply { isAccessible = true }
                val candidate = clientField.get(networkClient)
                if (candidate !is OkHttpClient) {
                    log("HTTP_HOOK", "installed=false reason=non_okhttp_factory")
                    continue
                }
                val client = candidate
                if (httpSlots.any { it.target === client }) continue
                val field = client.javaClass.getDeclaredField("eventListenerFactory")
                    .apply { isAccessible = true }
                val previous = field.get(client) as HttpEventListener.Factory
                val wrapper = HttpEventListener.Factory { call ->
                    val original = previous.create(call)
                    if (active && watched.contains(call.request().url.toString()))
                        HttpStages(original, call) else original
                }
                field.set(client, wrapper)
                check(field.get(client) === wrapper)
                httpSlots.add(Slot(client, field, previous, wrapper))
                found++
            } catch (error: Throwable) {
                log("HTTP_HOOK", "installed=false reason=${error.javaClass.simpleName}")
            }
        }
        log("HTTP_HOOK", "installed=${found > 0} clients=$found")
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        watched.clear()
        val slots = listOfNotNull(coilSlot) + httpSlots
        for (slot in slots) {
            try {
                if (slot.field.get(slot.target) === slot.wrapper)
                    slot.field.set(slot.target, slot.previous)
            } catch (error: Throwable) {
                log("HOOK_RESTORE_FAIL", "reason=${error.javaClass.simpleName}")
            }
        }
        log("HOOK_STOP", "restored=${slots.size}")
        coilSlot = null
        httpSlots.clear()
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun key(url: String) = StremioCMetaPerf.key(url)
    private fun log(event: String, detail: String, urlKey: String = "global") {
        StremioCMetaPerf.log(event, urlKey, detail)
    }

    private class CoilStages(
        private val original: CoilEventListener,
        private val initial: ImageRequest
    ) : CoilEventListener() {
        private val trace = ids.incrementAndGet()
        private val urlKey = key(initial.data.toString())
        private val born = now()
        private var started = -1L
        private var sized = -1L
        private var sizeMs = -1L
        private var fetchAt = -1L
        private var fetchEndAt = -1L
        private var fetchMs = -1L
        private var decodeAt = -1L
        private var decodeEndAt = -1L
        private var decodeMs = -1L
        private var transformAt = -1L
        private var transformMs = -1L
        private var source = "none"
        private fun age() = now() - born
        private fun event(name: String, data: String = "") {
            log(name, "trace=$trace ageMs=${age()} $data", urlKey)
        }
        init { event("COIL_NEW", "target=${initial.target?.javaClass?.simpleName ?: "none"}") }

        override fun onStart(request: ImageRequest) {
            original.onStart(request)
            started = now()
            event("COIL_START", "fromFactoryMs=${age()}")
        }
        override fun resolveSizeStart(request: ImageRequest, sizeResolver: SizeResolver) {
            original.resolveSizeStart(request, sizeResolver)
            sized = now()
            event("COIL_SIZE_START")
        }
        override fun resolveSizeEnd(request: ImageRequest, size: Size) {
            original.resolveSizeEnd(request, size)
            sizeMs = if (sized >= 0L) now() - sized else -1
            event("COIL_SIZE_END", "sizeMs=$sizeMs size=$size")
        }
        override fun mapStart(request: ImageRequest, input: Any) {
            original.mapStart(request, input)
            event("COIL_MAP_START")
        }
        override fun mapEnd(request: ImageRequest, output: Any) {
            original.mapEnd(request, output)
            event("COIL_MAP_END")
        }
        override fun keyStart(request: ImageRequest, input: Any) {
            original.keyStart(request, input)
            event("COIL_KEY_START")
        }
        override fun keyEnd(request: ImageRequest, output: String?) {
            original.keyEnd(request, output)
            event("COIL_KEY_END", "hasKey=${output != null} keyHash=${key(output ?: "")}")
        }
        override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
            original.fetchStart(request, fetcher, options)
            fetchAt = now()
            event("COIL_FETCH_START", "fetcher=${fetcher.javaClass.simpleName} " +
                "fromStartMs=${if (started >= 0L) fetchAt - started else -1} " +
                "diskRead=${options.diskCachePolicy.readEnabled} networkRead=${options.networkCachePolicy.readEnabled}")
        }
        override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
            original.fetchEnd(request, fetcher, options, result)
            fetchEndAt = now()
            fetchMs = if (fetchAt >= 0L) fetchEndAt - fetchAt else -1
            source = when (result) {
                is SourceFetchResult -> result.dataSource.toString()
                is ImageFetchResult -> result.dataSource.toString()
                else -> "none"
            }
            event("COIL_FETCH_END", "fetchMs=$fetchMs source=$source " +
                "mime=${(result as? SourceFetchResult)?.mimeType ?: "-"}")
        }
        override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
            original.decodeStart(request, decoder, options)
            decodeAt = now()
            event("COIL_DECODE_START", "decoder=${decoder.javaClass.simpleName} " +
                "afterFetchMs=${if (fetchEndAt >= 0L) decodeAt - fetchEndAt else -1}")
        }
        override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
            original.decodeEnd(request, decoder, options, result)
            decodeEndAt = now()
            decodeMs = if (decodeAt >= 0L) decodeEndAt - decodeAt else -1
            event("COIL_DECODE_END", "decodeMs=$decodeMs sampled=${result?.isSampled ?: false}")
        }
        override fun transformStart(request: ImageRequest, input: Bitmap) {
            original.transformStart(request, input)
            transformAt = now()
            event("COIL_TRANSFORM_START")
        }
        override fun transformEnd(request: ImageRequest, output: Bitmap) {
            original.transformEnd(request, output)
            transformMs = if (transformAt >= 0L) now() - transformAt else -1
            event("COIL_TRANSFORM_END", "transformMs=$transformMs")
        }
        override fun transitionStart(request: ImageRequest, transition: Transition) {
            original.transitionStart(request, transition)
            event("COIL_TRANSITION_START", "transition=${transition.javaClass.simpleName}")
        }
        override fun transitionEnd(request: ImageRequest, transition: Transition) {
            original.transitionEnd(request, transition)
            event("COIL_TRANSITION_END")
        }
        private fun summary(status: String, detail: String = "") {
            val end = now()
            event("COIL_SUMMARY", "status=$status totalMs=${end - born} " +
                "startWaitMs=${if (started >= 0) started - born else -1} " +
                "sizeMs=$sizeMs preFetchMs=${if (started >= 0 && fetchAt >= 0) fetchAt - started else -1} " +
                "fetchMs=$fetchMs fetchPendingMs=${if (fetchAt >= 0 && fetchEndAt < 0) end - fetchAt else -1} " +
                "decodeMs=$decodeMs decodePendingMs=${if (decodeAt >= 0 && decodeEndAt < 0) end - decodeAt else -1} transformMs=$transformMs " +
                "afterDecodeMs=${if (decodeEndAt >= 0) end - decodeEndAt else -1} " +
                "source=$source $detail")
        }
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            original.onSuccess(request, result)
            summary("success", "finalSource=${result.dataSource} sampled=${result.isSampled}")
        }
        override fun onError(request: ImageRequest, result: ErrorResult) {
            original.onError(request, result)
            summary("error", "reason=${result.throwable.javaClass.simpleName}")
        }
        override fun onCancel(request: ImageRequest) {
            original.onCancel(request)
            summary("cancel")
        }
    }

    private class HttpStages(
        private val original: HttpEventListener,
        call: Call
    ) : HttpEventListener() {
        private val id = ids.incrementAndGet()
        private val urlKey = key(call.request().url.toString())
        private val born = now()
        private var dnsAt = -1L
        private var connectAt = -1L
        private var tlsAt = -1L
        private var requestAt = -1L
        private var requestEndAt = -1L
        private var responseAt = -1L
        private var bodyAt = -1L
        private var dnsMs = -1L
        private var connectMs = -1L
        private var tlsMs = -1L
        private var headersMs = -1L
        private var bodyMs = -1L
        private var status = -1
        private var bytes = -1L
        private fun event(name: String, details: String = "") =
            log(name, "callId=$id ageMs=${now() - born} $details", urlKey)

        override fun callStart(call: Call) {
            original.callStart(call)
            event("COIL_HTTP_START", "host=${call.request().url.host}")
        }
        override fun dnsStart(call: Call, domainName: String) {
            original.dnsStart(call, domainName)
            dnsAt = now()
            event("COIL_HTTP_DNS_START")
        }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            original.dnsEnd(call, domainName, inetAddressList)
            dnsMs = if (dnsAt >= 0) now() - dnsAt else -1
            event("COIL_HTTP_DNS_END", "dnsMs=$dnsMs addresses=${inetAddressList.size}")
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            original.connectStart(call, inetSocketAddress, proxy)
            connectAt = now()
            event("COIL_HTTP_CONNECT_START")
        }
        override fun secureConnectStart(call: Call) {
            original.secureConnectStart(call)
            tlsAt = now()
            event("COIL_HTTP_TLS_START")
        }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            original.secureConnectEnd(call, handshake)
            tlsMs = if (tlsAt >= 0) now() - tlsAt else -1
            event("COIL_HTTP_TLS_END", "tlsMs=$tlsMs")
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            original.connectEnd(call, inetSocketAddress, proxy, protocol)
            connectMs = if (connectAt >= 0) now() - connectAt else -1
            event("COIL_HTTP_CONNECT_END", "connectMs=$connectMs protocol=${protocol ?: "none"}")
        }
        override fun connectFailed(
            call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
            protocol: Protocol?, ioe: IOException
        ) {
            original.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
            event("COIL_HTTP_CONNECT_FAIL", "reason=${ioe.javaClass.simpleName}")
        }
        override fun connectionAcquired(call: Call, connection: Connection) {
            original.connectionAcquired(call, connection)
            event("COIL_HTTP_CONNECTION", "connectionKey=${System.identityHashCode(connection).toUInt().toString(16)} " +
                "reused=${connectAt < 0} protocol=${connection.protocol()}")
        }
        override fun connectionReleased(call: Call, connection: Connection) {
            original.connectionReleased(call, connection)
        }
        override fun requestHeadersStart(call: Call) {
            original.requestHeadersStart(call)
            requestAt = now()
            event("COIL_HTTP_REQUEST_HEADERS_START", "beforeSendMs=${requestAt - born}")
        }
        override fun requestHeadersEnd(call: Call, request: Request) {
            original.requestHeadersEnd(call, request)
            requestEndAt = now()
            event("COIL_HTTP_REQUEST_HEADERS_END", "sendMs=${if (requestAt >= 0) requestEndAt - requestAt else -1}")
        }
        override fun requestBodyStart(call: Call) {
            original.requestBodyStart(call)
        }
        override fun requestBodyEnd(call: Call, byteCount: Long) {
            original.requestBodyEnd(call, byteCount)
        }
        override fun responseHeadersStart(call: Call) {
            original.responseHeadersStart(call)
            responseAt = now()
            event("COIL_HTTP_RESPONSE_HEADERS_START",
                "waitMs=${if (requestEndAt >= 0) responseAt - requestEndAt else -1}")
        }
        override fun responseHeadersEnd(call: Call, response: Response) {
            original.responseHeadersEnd(call, response)
            status = response.code
            headersMs = now() - born
            event("COIL_HTTP_RESPONSE_HEADERS_END",
                "status=$status headerMs=$headersMs protocol=${response.protocol} " +
                    "cfCache=${response.header("CF-Cache-Status") ?: "-"} " +
                    "age=${response.header("Age") ?: "-"} " +
                    "serverTiming=${response.header("Server-Timing")?.take(90)?.replace(' ', '_') ?: "-"}")
        }
        override fun responseBodyStart(call: Call) {
            original.responseBodyStart(call)
            bodyAt = now()
            event("COIL_HTTP_BODY_START")
        }
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            original.responseBodyEnd(call, byteCount)
            bytes = byteCount
            bodyMs = if (bodyAt >= 0) now() - bodyAt else -1
            event("COIL_HTTP_BODY_END", "bodyMs=$bodyMs bytes=$bytes")
        }
        override fun callEnd(call: Call) {
            original.callEnd(call)
            event("COIL_HTTP_SUMMARY",
                "status=$status totalMs=${now() - born} " +
                    "dnsMs=$dnsMs connectMs=$connectMs tlsMs=$tlsMs " +
                    "headersMs=$headersMs bodyMs=$bodyMs bytes=$bytes")
        }
        override fun callFailed(call: Call, ioe: IOException) {
            original.callFailed(call, ioe)
            event("COIL_HTTP_FAIL", "elapsedMs=${now() - born} " +
                "status=$status reason=${ioe.javaClass.simpleName}")
        }
    }
}
