package com.hsp1020

import android.content.Context
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Warm Coil's existing memory/disk caches for every loaded catalog page, without binding views. */
internal class CatalogPosterPreloader(private val origin: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limit = Semaphore(16)
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())

    fun enqueue(context: Context?, cards: List<SearchResponse>) {
        val application = context?.applicationContext ?: return
        var started = 0
        for (card in cards) {
            val url = card.posterUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: continue
            val token = url + "|" + card.posterHeaders.orEmpty().hashCode()
            if (!seen.add(token)) continue
            started++
            scope.launch {
                try {
                    limit.withPermit {
                        val headers = NetworkHeaders.Builder().apply {
                            this["User-Agent"] = USER_AGENT
                            card.posterHeaders?.forEach { (key, value) -> this[key] = value }
                        }.build()
                        SingletonImageLoader.get(application).execute(
                            ImageRequest.Builder(application).data(url)
                                .size(Size(240, 360)).scale(Scale.FILL)
                                .precision(Precision.INEXACT).httpHeaders(headers).build()
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Native ImageViews retain their own fallback and retry behavior.
                }
            }
        }
        if (started > 0) StremioCMetaPerf.log("POSTER_PREFETCH", origin,
            "scheduled=$started total=${cards.size} seen=${seen.size}")
    }

    fun reset() {
        scope.coroutineContext.cancelChildren()
        seen.clear()
    }

    fun close() {
        scope.cancel()
        seen.clear()
    }
}

internal object CatalogPosterPreloads {
    val home = CatalogPosterPreloader("home")
}
