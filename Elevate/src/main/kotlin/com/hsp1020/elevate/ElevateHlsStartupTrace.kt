package com.hsp1020.elevate

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * Diagnostic-only Media3 analytics hook. Use the interface loaded by the host app:
 * compiled AnalyticsListener overrides can crash on different Media3 ABIs.
 */
internal class ElevateHlsStartupTrace {
    private class Registration(val listener: Any, val remove: Method)
    private val main = Handler(Looper.getMainLooper())
    private val hosts = WeakHashMap<FragmentActivity, FragmentManager.FragmentLifecycleCallbacks>()
    private val watchers = WeakHashMap<Fragment, Runnable>()
    private val owners = WeakHashMap<Fragment, WeakReference<Any>>()
    private val registrations = WeakHashMap<Any, Registration>()
    private val unsupported = WeakHashMap<Any, Boolean>()
    private var running = false

    private fun log(stage: String, message: String) {
        Log.i("ElevateHlsStartupTrace", "stage=$stage $message")
    }

    fun start() {
        if (running) return
        running = true
        PipLibraryNavigation.currentPlayerHost()?.let(::track)
    }

    fun stop() {
        if (!running) return
        running = false
        hosts.toMap().forEach { (host, callbacks) ->
            runCatching { host.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks) }
        }
        hosts.clear()
        watchers.values.forEach(main::removeCallbacks)
        watchers.clear()
        owners.clear()
        registrations.toMap().forEach { (player, registration) ->
            runCatching { registration.remove.invoke(player, registration.listener) }
        }
        registrations.clear()
        unsupported.clear()
    }

    fun track(activity: Activity) {
        val host = activity as? FragmentActivity ?: return
        if (!running || hosts.containsKey(host)) return
        val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, state: android.os.Bundle?) {
                watch(f)
            }
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                watch(f)
            }
            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                unwatch(f)
            }
        }
        hosts[host] = callbacks
        host.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                if (fragment.view != null) watch(fragment)
                runCatching { scan(fragment.childFragmentManager) }
            }
        }
        runCatching { scan(host.supportFragmentManager) }
    }

    fun untrack(activity: Activity) {
        val host = activity as? FragmentActivity ?: return
        hosts.remove(host)?.let { callbacks ->
            runCatching { host.supportFragmentManager.unregisterFragmentLifecycleCallbacks(callbacks) }
        }
        owners.keys.toList().filter { it.activity === host }.forEach(::unwatch)
    }

    private fun watch(fragment: Fragment) {
        if (fragment !is AbstractPlayerFragment<*>) return
        watchers.remove(fragment)?.let(main::removeCallbacks)
        val reference = WeakReference(fragment)
        var checks = 0
        val task = object : Runnable {
            override fun run() {
                val target = reference.get() as? AbstractPlayerFragment<*> ?: return
                if (!running || !target.isAdded || target.view == null) return
                val player = target.playerView?.player
                if (player != null) {
                    val previous = owners[target]?.get()
                    if (previous !== player) {
                        owners[target] = WeakReference(player)
                        if (previous != null && owners.values.none { it.get() === previous }) detach(previous)
                    }
                    if (!registrations.containsKey(player) && !unsupported.containsKey(player)) attach(player)
                }
                main.postDelayed(this, if (++checks < 60) 100L else 1000L)
            }
        }
        watchers[fragment] = task
        main.post(task)
    }

    private fun unwatch(fragment: Fragment) {
        watchers.remove(fragment)?.let(main::removeCallbacks)
        val player = owners.remove(fragment)?.get() ?: return
        if (owners.values.none { it.get() === player }) detach(player)
    }

    private fun detach(player: Any) {
        val registered = registrations.remove(player) ?: return
        runCatching { registered.remove.invoke(player, registered.listener) }
        log("DETACH", "id=${System.identityHashCode(player)}")
    }

    private fun field(source: Any?, key: String): Any? =
        runCatching { source?.javaClass?.getField(key)?.get(source) }.getOrNull()

    private fun number(source: Any?, key: String): Long? = (field(source, key) as? Number)?.toLong()

    private fun uri(source: Any?): Uri? =
        (field(source, "uri") ?: field(field(source, "dataSpec"), "uri")) as? Uri

    private fun attach(player: Any) {
        val id = System.identityHashCode(player)
        val attempt = runCatching {
            val loader = player.javaClass.classLoader
            val type = Class.forName("androidx.media3.exoplayer.analytics.AnalyticsListener", false, loader)
            val add = player.javaClass.methods.firstOrNull {
                it.name == "addAnalyticsListener" && it.parameterTypes.size == 1 && it.parameterTypes[0] == type
            } ?: error("addAnalyticsListener unavailable")
            val remove = player.javaClass.methods.firstOrNull {
                it.name == "removeAnalyticsListener" && it.parameterTypes.size == 1 && it.parameterTypes[0] == type
            } ?: error("removeAnalyticsListener unavailable")

            var cycleStart = SystemClock.elapsedRealtime()
            val requests = HashMap<Long, Long>()
            var firstFrame = false
            fun elapsed() = SystemClock.elapsedRealtime() - cycleStart
            val handler = InvocationHandler { proxy, method, args ->
                try {
                    when (method.name) {
                        "equals" -> proxy === args?.getOrNull(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "ElevateHlsStartupTraceProxy"
                        else -> {
                            val parameters = args.orEmpty()
                            when (method.name) {
                                "onMediaItemTransition" -> {
                                    cycleStart = SystemClock.elapsedRealtime()
                                    requests.clear()
                                    firstFrame = false
                                    log("MEDIA_CHANGE", "id=$id ms=0")
                                }
                                "onPlaybackStateChanged" -> {
                                    val state = (parameters.lastOrNull() as? Number)?.toInt()
                                    val name = when (state) {
                                        1 -> "IDLE"
                                        2 -> "BUFFERING"
                                        3 -> "READY"
                                        4 -> "ENDED"
                                        else -> "UNKNOWN"
                                    }
                                    log("STATE", "id=$id ms=${elapsed()} value=$name")
                                }
                                "onIsLoadingChanged" -> log("IS_LOADING", "id=$id ms=${elapsed()} loading=${parameters.lastOrNull()}")
                                "onLoadStarted", "onLoadCompleted", "onLoadCanceled", "onLoadError" -> {
                                    val info = parameters.getOrNull(1)
                                    val data = parameters.getOrNull(2)
                                    val taskId = number(info, "loadTaskId") ?: -1L
                                    val path = uri(info)
                                    val ext = path?.lastPathSegment?.substringAfterLast('.', "")?.take(12)
                                    val kind = when {
                                        ext.equals("m3u8", true) || ext.equals("mpd", true) || number(data, "dataType") == 4L -> "MANIFEST"
                                        number(data, "dataType") == 1L || ext.equals("ts", true) || ext.equals("m4s", true) -> "SEGMENT"
                                        else -> "OTHER"
                                    }
                                    val now = SystemClock.elapsedRealtime()
                                    if (method.name == "onLoadStarted") requests[taskId] = now
                                    val requestMs = if (method.name == "onLoadStarted") null else requests.remove(taskId)?.let { now - it }
                                    val error = parameters.firstOrNull { it is Throwable } as? Throwable
                                    log("LOAD", "id=$id ms=${elapsed()} event=${method.name} task=$taskId kind=$kind type=${number(data, "dataType")} host=${path?.host} ext=$ext networkMs=$requestMs loadMs=${number(info, "loadDurationMs")} bytes=${number(info, "bytesLoaded")} error=${error?.javaClass?.simpleName}")
                                }
                                "onDownstreamFormatChanged", "onVideoInputFormatChanged", "onAudioInputFormatChanged" -> {
                                    val data = parameters.getOrNull(1)
                                    val format = field(data, "trackFormat") ?: data
                                    log("FORMAT", "id=$id ms=${elapsed()} method=${method.name} track=${number(data, "trackType")} mime=${field(format, "sampleMimeType")}")
                                }
                                "onVideoDecoderInitialized", "onAudioDecoderInitialized" -> {
                                    log("DECODER", "id=$id ms=${elapsed()} method=${method.name} decoder=${parameters.getOrNull(1)} initMs=${(parameters.lastOrNull() as? Number)?.toLong()}")
                                }
                                "onRenderedFirstFrame" -> {
                                    if (!firstFrame) {
                                        firstFrame = true
                                        log("FIRST_FRAME", "id=$id ms=${elapsed()}")
                                    }
                                }
                                "onPlayerError" -> {
                                    val failure = parameters.firstOrNull { it is Throwable } as? Throwable
                                    log("PLAYER_ERROR", "id=$id ms=${elapsed()} error=${failure?.javaClass?.simpleName}")
                                }
                            }
                            null
                        }
                    }
                } catch (failure: Throwable) {
                    log("TRACE_ERROR", "id=$id callback=${method.name} error=${failure.javaClass.simpleName}")
                    null
                }
            }
            val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
            add.invoke(player, proxy)
            registrations[player] = Registration(proxy, remove)
            log("ATTACH", "id=$id player=${player.javaClass.simpleName} media3Interface=${type.methods.firstOrNull { it.name == "onLoadStarted" }?.parameterTypes?.size}")
        }
        attempt.onFailure {
            unsupported[player] = true
            log("HOOK_UNAVAILABLE", "id=$id error=${it.javaClass.simpleName} reason=${it.message?.take(100)}")
        }
    }
}
