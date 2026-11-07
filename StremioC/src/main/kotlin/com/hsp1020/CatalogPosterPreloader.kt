package com.hsp1020

import android.content.Context
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Warm Coil's existing memory/disk caches for every loaded catalog page, without binding views. */
internal class CatalogPosterPreloader(private val origin: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limit = Semaphore(16)
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())
    private val nativeClaims = ConcurrentHashMap.newKeySet<String>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private fun token(card: SearchResponse, url: String): String =
        url + "|" + card.posterHeaders.orEmpty().hashCode()

    /** A bound native card owns this URL; stop redundant disk warming immediately. */
    fun nativeBound(card: SearchResponse) {
        val url = card.posterUrl ?: return
        val key = token(card, url)
        nativeClaims.add(key)
        seen.add(key)
        jobs.remove(key)?.let { job ->
            if (job.isActive) {
                job.cancel()
                StremioCMetaPerf.log("POSTER_PREFETCH_NATIVE", StremioCMetaPerf.key(url),
                    "origin=$origin action=cancel_prefetch")
            }
        }
    }

    fun enqueue(context: Context?, cards: List<SearchResponse>) {
        val application = context?.applicationContext ?: return
        var started = 0
        for (card in cards) {
            val url = card.posterUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: continue
            val key = token(card, url)
            if (!seen.add(key)) continue
            started++
            // Let the real ImageView bind first, then warm only the remaining page cards.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(200L)
                    if (key in nativeClaims) return@launch
                    limit.withPermit {
                        if (key in nativeClaims) return@withPermit
                        val headers = NetworkHeaders.Builder().apply {
                            this["User-Agent"] = USER_AGENT
                            card.posterHeaders?.forEach { (name, value) -> this[name] = value }
                        }.build()
                        // Only populate Coil's shared disk cache. A tiny decode avoids
                        // allocating hundreds of offscreen poster-sized bitmaps.
                        SingletonImageLoader.get(application).execute(
                            ImageRequest.Builder(application).data(url)
                                .size(Size(40, 60)).scale(Scale.FILL)
                                .precision(Precision.INEXACT)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .httpHeaders(headers).build()
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Native ImageViews retain their own fallback and retry behavior.
                }
            }
            jobs[key] = job
            job.invokeOnCompletion { jobs.remove(key, job) }
            job.start()
        }
        if (started > 0) StremioCMetaPerf.log("POSTER_PREFETCH", origin,
            "scheduled=$started total=${cards.size} seen=${seen.size} " +
                "warm=40x60 diskOnly=true delayMs=200")
    }

    fun reset() {
        scope.coroutineContext.cancelChildren()
        jobs.clear()
        nativeClaims.clear()
        seen.clear()
    }

    fun close() {
        scope.cancel()
        jobs.clear()
        nativeClaims.clear()
        seen.clear()
    }
}

internal object CatalogPosterPreloads {
    val home = CatalogPosterPreloader("home")
}
