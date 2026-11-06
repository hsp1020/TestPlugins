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
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import coil3.SingletonImageLoader
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
    internal data class Result(val success: Boolean, val source: String = "", val error: String = "")
    private data class Running(val animator: ValueAnimator, val overlay: WipeDrawable)
    private val active = WeakHashMap<ImageView, Running>()
    private const val DURATION_MS = 500L
    private data class RequestKey(val url: String, val size: Size, val scale: Scale)
    private class Flight(val request: Deferred<ImageResult>, var subscribers: Int = 0)
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageFlights = HashMap<RequestKey, Flight>()

    // Exact-size Coil work shares a decoded result. Different sizes serialize
    // the same URL's fetch, allowing Coil to reuse its disk cache before decoding.
    private data class UrlGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val urlGates = HashMap<String, UrlGate>()

    private fun trace(url: String, event: String, info: String) {
        Log.d("ElevatePoster", "event=$event urlKey=${url.hashCode().toUInt().toString(16)} $info")
    }

    private suspend fun executeImage(
        context: Context, url: String, size: Size, scale: Scale
    ): ImageResult {
        val gate = synchronized(urlGates) {
            urlGates.getOrPut(url) { UrlGate() }.also { it.users++ }
        }
        try {
            if (gate.mutex.isLocked) trace(url, "IMAGE_WAIT", "size=$size")
            gate.mutex.lock()
            try {
                val request = ImageRequest.Builder(context.applicationContext).data(url)
                    .size(size).scale(scale).precision(Precision.INEXACT)
                    .httpHeaders(NetworkHeaders.Builder().apply {
                        this["User-Agent"] = USER_AGENT
                    }.build()).build()
                return SingletonImageLoader.get(context.applicationContext).execute(request)
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
        context: Context, url: String, size: Size, scale: Scale, origin: String
    ): ImageResult {
        val key = RequestKey(url, size, scale)
        var joined = false
        val flight = synchronized(imageFlights) {
            (imageFlights[key]?.also { joined = true } ?: run {
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
        trace(url, if (joined) "IMAGE_JOIN" else "IMAGE_NEW",
            "origin=$origin size=$size scale=$scale")
        flight.request.start()
        val started = SystemClock.elapsedRealtime()
        try {
            val result = flight.request.await()
            val source = when (result) {
                is SuccessResult -> result.dataSource.name
                is ErrorResult -> result.throwable.javaClass.simpleName
                else -> "UNKNOWN"
            }
            trace(url, "IMAGE_RESULT",
                "origin=$origin source=$source shared=$joined elapsedMs=${SystemClock.elapsedRealtime() - started}")
            return result
        } catch (cancelled: CancellationException) {
            trace(url, "IMAGE_CANCEL", "origin=$origin")
            throw cancelled
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

    suspend fun load(image: ImageView, url: String, origin: String = "library", valid: () -> Boolean): Result {
        // Match the native ImageView's size/scale to reuse Coil cache entries.
        // ViewSizeResolver waits for layout instead of decoding at an incorrect 1x1 size.
        val outcome = try {
            if (!image.isAttachedToWindow) return Result(false, error = "stale")
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
            loadSharedImage(image.context, url, size, scale, origin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result(false, error = failure.javaClass.simpleName)
        }
        if (!valid() || !image.isAttachedToWindow) return Result(false, error = "stale")
        if (outcome is ErrorResult)
            return Result(false, error = outcome.throwable.javaClass.simpleName)
        val success = outcome as? SuccessResult ?: return Result(false, error = "no_image")
        val custom = success.image.asDrawable(image.resources).mutate()
        cancel(image)
        val previous = image.drawable
        // Only now cancel a possibly still-running native request; never before decoding.
        image.dispose()
        // Cached artwork is already available: avoid a redundant 500ms transition.
        if (success.dataSource.name != "NETWORK" || image.width <= 0 || image.height <= 0) {
            image.setImageDrawable(custom)
            return Result(true, source = success.dataSource.name)
        }
        image.setImageDrawable(previous ?: image.context.getDrawable(R.drawable.default_cover))
        val wipe = WipeDrawable(custom)
        wipe.bounds = Rect(0, 0, image.width, image.height)
        image.overlay.add(wipe)
        val animation = ValueAnimator.ofFloat(0f, 1f).apply { duration = DURATION_MS }
        val running = Running(animation, wipe)
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
                }
            }
        })
        animation.start()
        return Result(true, source = success.dataSource.name)
    }
}
