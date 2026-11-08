package com.hsp1020.elevate

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.content.Context
import android.widget.ImageView
import coil3.SingletonImageLoader
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.IOException
import coil3.request.ImageResult
import coil3.asDrawable
import coil3.dispose
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.size.ViewSizeResolver
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import java.util.WeakHashMap
import kotlin.math.max

/** Keep the native cover visible until Coil finishes; only the decoded artwork wipes in. */
internal object PosterWipe {
    internal data class Result(val success: Boolean, val error: String = "")
    private data class Running(
        val url: String, val base: Drawable?, val animator: ValueAnimator, val overlay: WipeDrawable
    )
    private data class Displayed(val url: String, val drawable: java.lang.ref.WeakReference<Drawable>)
    private val active = WeakHashMap<ImageView, Running>()
    private val displayed = WeakHashMap<ImageView, Displayed>()

    private fun isDisplayed(image: ImageView, url: String): Boolean =
        displayed[image]?.let { it.url == url && it.drawable.get() === image.drawable } == true ||
            active[image]?.let { it.url == url && it.base === image.drawable } == true

    private fun recordDisplayed(image: ImageView, url: String, drawable: Drawable) {
        displayed[image] = Displayed(url, java.lang.ref.WeakReference(drawable))
    }
    private const val DURATION_MS = 500L
    private data class RequestKey(val url: String, val size: Size, val scale: Scale)
    private class Flight(val request: Deferred<ImageResult>, var subscribers: Int = 0)
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageFlights = HashMap<RequestKey, Flight>()

    // Exact-size Coil work shares a decoded result. Different sizes serialize
    // the same URL's fetch, allowing Coil to reuse its disk cache before decoding.
    private data class UrlGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val urlGates = HashMap<String, UrlGate>()
    // A cache probe can never trigger an HTTP call. Only use it when another
    // size for the same URL is already filling the cache.
    private val cacheOnlyFetcher = NetworkFetcher.Factory(networkClient = {
        object : NetworkClient {
            override suspend fun <T> executeRequest(
                request: NetworkRequest, block: suspend (NetworkResponse) -> T
            ): T = throw IOException("Cache miss")
        }
    })

    // Preserve the existing custom-poster dispatcher and Coil disk-cache sharing.
    private val posterFetcher by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpNetworkFetcherFactory(OkHttpClient.Builder().dispatcher(dispatcher).build())
    }

    private suspend fun executeImage(
        context: Context, url: String, size: Size, scale: Scale
    ): ImageResult {
        val gate = synchronized(urlGates) {
            urlGates.getOrPut(url) { UrlGate() }.also { it.users++ }
        }
        try {
            val loader = SingletonImageLoader.get(context.applicationContext)
            val request = ImageRequest.Builder(context.applicationContext).data(url)
                .size(size).scale(scale).precision(Precision.INEXACT)
                .httpHeaders(NetworkHeaders.Builder().apply {
                    this["User-Agent"] = USER_AGENT
                }.build())
            if (gate.mutex.isLocked) {
                // Do not wait behind a slow network request when Coil can
                // immediately serve a previously decoded or cached image.
                val cached = loader.execute(request
                    .networkCachePolicy(CachePolicy.DISABLED)
                    .fetcherFactory(cacheOnlyFetcher).build())
                if (cached is SuccessResult) {
                    return cached
                }
            }
            gate.mutex.lock()
            try {
                return loader.execute(request
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .fetcherFactory(posterFetcher).build())
            } finally {
                gate.mutex.unlock()
            }
        } finally {
            synchronized(urlGates) {
                gate.users--
                if (gate.users == 0 && urlGates[url] === gate) urlGates.remove(url)
            }
        }
    }

    // Never share a decoded image across distinct output dimensions.
    private suspend fun loadSharedImage(
        context: Context, url: String, size: Size, scale: Scale
    ): ImageResult {
        val key = RequestKey(url, size, scale)
        val flight = synchronized(imageFlights) {
            (imageFlights[key] ?: run {
                val job = imageScope.async(start = CoroutineStart.LAZY) {
                    executeImage(context, url, size, scale)
                }
                Flight(job).also { created ->
                    imageFlights[key] = created
                    job.invokeOnCompletion {
                        synchronized(imageFlights) {
                            if (imageFlights[key] === created) imageFlights.remove(key)
                        }
                    }
                }
            }).also { it.subscribers++ }
        }
        flight.request.start()
        try {
            return flight.request.await()
        } finally {
            val cancel = synchronized(imageFlights) {
                flight.subscribers--
                if (flight.subscribers == 0 && flight.request.isActive) {
                    if (imageFlights[key] === flight) imageFlights.remove(key)
                    true
                } else false
            }
            if (cancel) flight.request.cancel()
        }
    }

    private class WipeDrawable(private val image: Drawable) : Drawable() {
        var progress = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val area = bounds
            if (area.isEmpty) return
            val save = canvas.save()
            canvas.clipRect(area.left.toFloat(), area.top.toFloat(),
                area.left + area.width() * progress, area.bottom.toFloat())
            val width = image.intrinsicWidth.coerceAtLeast(1)
            val height = image.intrinsicHeight.coerceAtLeast(1)
            val scale = max(area.width().toFloat() / width, area.height().toFloat() / height)
            val targetW = (width * scale).toInt()
            val targetH = (height * scale).toInt()
            val x = area.left + (area.width() - targetW) / 2
            val y = area.top + (area.height() - targetH) / 2
            image.setBounds(x, y, x + targetW, y + targetH)
            image.draw(canvas)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { image.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { image.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun cancel(image: ImageView) {
        active.remove(image)?.let { state ->
            state.animator.cancel()
            image.overlay.remove(state.overlay)
        }
    }

    fun ensurePlaceholder(image: ImageView) {
        if (image.drawable == null) image.setImageResource(R.drawable.default_cover)
    }

    /** Warm the existing Coil cache without attaching or modifying an ImageView. */
    suspend fun prefetch(context: Context, url: String, width: Int, height: Int): Result {
        val outcome = try {
            loadSharedImage(context, url, Size(width, height), Scale.FILL)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result(false, error = failure.javaClass.simpleName)
        }
        return when (outcome) {
            is SuccessResult -> Result(true)
            is ErrorResult -> Result(false, error = outcome.throwable.javaClass.simpleName)
            else -> Result(false, error = "no_image")
        }
    }

    suspend fun load(image: ImageView, url: String, valid: () -> Boolean): Result {
        // Match the native ImageView's size/scale to reuse Coil cache entries.
        // ViewSizeResolver waits for layout instead of decoding at an incorrect 1x1 size.
        val outcome = try {
            if (!image.isAttachedToWindow || !valid()) return Result(false, error = "stale")
            if (isDisplayed(image, url))
                return Result(true)
            val scale = when (image.scaleType) {
                ImageView.ScaleType.CENTER_CROP, ImageView.ScaleType.FIT_XY -> Scale.FILL
                else -> Scale.FIT
            }
            val size = when (image.scaleType) {
                ImageView.ScaleType.CENTER, ImageView.ScaleType.MATRIX -> Size.ORIGINAL
                else -> ViewSizeResolver(image).size()
            }
            if (!valid() || !image.isAttachedToWindow)
                return Result(false, error = "stale")
            loadSharedImage(image.context, url, size, scale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result(false, error = failure.javaClass.simpleName)
        }
        if (!valid() || !image.isAttachedToWindow) return Result(false, error = "stale")
        if (outcome is ErrorResult)
            return Result(false, error = outcome.throwable.javaClass.simpleName)
        val success = outcome as? SuccessResult ?: return Result(false, error = "no_image")
        if (isDisplayed(image, url))
            return Result(true)
        val custom = success.image.asDrawable(image.resources).mutate()
        cancel(image)
        val previous = image.drawable
        // Only now cancel a possibly still-running native request; never before decoding.
        image.dispose()
        // A genuinely different poster wipes in even when Coil returned a cached image.
        if (image.width <= 0 || image.height <= 0) {
            image.setImageDrawable(custom)
            recordDisplayed(image, url, custom)
            return Result(true)
        }
        image.setImageDrawable(previous ?: image.context.getDrawable(R.drawable.default_cover))
        val wipe = WipeDrawable(custom)
        wipe.bounds = Rect(0, 0, image.width, image.height)
        image.overlay.add(wipe)
        val animation = ValueAnimator.ofFloat(0f, 1f).apply { duration = DURATION_MS }
        val running = Running(url, image.drawable, animation, wipe)
        active[image] = running
        animation.addUpdateListener {
            if (active[image] === running) wipe.progress = it.animatedValue as Float
        }
        animation.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (active[image] === running) {
                    active.remove(image)
                    image.overlay.remove(wipe)
                    image.setImageDrawable(custom)
                    recordDisplayed(image, url, custom)
                }
            }
        })
        animation.start()
        return Result(true)
    }
}
