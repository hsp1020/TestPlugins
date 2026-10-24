package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive

/** Passive diagnostic: observes the core player but never reloads or relayouts it. */
internal class ElevatePlayerFrameTrace(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private var fragment: GeneratorPlayer? = null
    private var model: PlayerGeneratorViewModel? = null
    private var tree: ViewTreeObserver? = null
    private var onDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var unsubscribe: (() -> Unit)? = null
    private val layoutListeners = mutableMapOf<View, View.OnLayoutChangeListener>()
    private var session = 0
    private var event = 0
    private var lastPlayer: Any? = null
    private var observed = false
    private var lastInstance: Int? = null
    private var lastState: Int? = null
    private var lastDraw = 0L
    private var layoutWindow = 0L
    private var layouts = 0

    private fun log(stage: String, detail: String = "") {
        Log.i("Elevate-PlayerTrace", "session=" + session + " event=" + (++event) +
            " uptimeMs=" + SystemClock.elapsedRealtime() + " stage=" + stage + " " + detail)
    }

    private fun source(): String {
        val state = model?.state ?: return "model=none"
        return "instance=" + state.instance +
            " provider=" + (state.generatorState?.response?.apiName ?: "none")
    }

    private val lifecycle = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, saved: Bundle?) {
            attach(f)
        }
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            attach(f)
            if (f === fragment) log("resumed", source())
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === fragment) {
                log("view-destroyed", source())
                detach()
            }
        }
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let { bindHost(it) }
    }
    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
        detach()
        host = null
    }
    private fun bindHost(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(lifecycle, true)
        fun scan(fm: FragmentManager) {
            for (f in fm.fragments) {
                if (f.view != null) attach(f)
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }
    private fun attach(f: Fragment) {
        if (f !is GeneratorPlayer || !f.isAdded || f.view == null || fragment === f) return
        detach()
        fragment = f
        model = ViewModelProvider(f)[PlayerGeneratorViewModel::class.java]
        session++
        event = 0
        log("fragment-attach", source())
        val vm = model ?: return
        val links = Observer<VideoLive<Set<VideoLink>>> {
            log("links-event", source())
            sample("links")
        }
        val loading = Observer<VideoLive<Resource<Unit>>> {
            log("loading-event", source())
            sample("loading")
        }
        vm.currentLinks.observe(f.viewLifecycleOwner, links)
        vm.loadingLinks.observe(f.viewLifecycleOwner, loading)
        unsubscribe = {
            vm.currentLinks.removeObserver(links)
            vm.loadingLinks.removeObserver(loading)
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            sample("draw")
            val now = SystemClock.elapsedRealtime()
            if (lastDraw != 0L && now - lastDraw >= 80L) {
                log("slow-frame-gap", "milliseconds=" + (now - lastDraw) + " " + source())
            }
            lastDraw = now
            true
        }
        f.view?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            tree = it
            onDraw = listener
            it.addOnPreDrawListener(listener)
        }
        for (name in listOf("exo_content_frame", "extra_brightness_overlay",
            "start_panel", "end_panel")) {
            val root = f.view ?: continue
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            val view = if (id > 0) root.findViewById<View>(id) else null
            if (view == null) continue
            val onChange = View.OnLayoutChangeListener { v, l, t, r, b, ol, ot, oright, ob ->
                val now = SystemClock.elapsedRealtime()
                if (now - layoutWindow >= 1000L) {
                    if (layouts > 0) log("layout-window", "count=" + layouts)
                    layouts = 0
                    layoutWindow = now
                }
                layouts++
                if (layouts <= 5) {
                    log("layout", "view=" + name + " sameBounds=" +
                        (l == ol && t == ot && r == oright && b == ob) +
                        " old=" + (oright-ol) + "x" + (ob-ot) +
                        " new=" + (r-l) + "x" + (b-t) +
                        " requested=" + v.isLayoutRequested)
                }
            }
            view.addOnLayoutChangeListener(onChange)
            layoutListeners[view] = onChange
        }
        sample("initial")
    }
    private fun sample(trigger: String) {
        val player = fragment?.playerView?.player
        val instance = model?.state?.instance
        if (lastInstance != instance) {
            log("instance", "old=" + lastInstance + " new=" + instance +
                " trigger=" + trigger + " " + source())
            lastInstance = instance
        }
        if (!observed || lastPlayer !== player) {
            log("player-attachment", "oldId=" + (lastPlayer?.let(System::identityHashCode) ?: 0) +
                " newId=" + (player?.let(System::identityHashCode) ?: 0) +
                " trigger=" + trigger + " " + source())
            lastPlayer = player
            lastState = null
            observed = true
        }
        if (player != null) {
            val state = runCatching { player.playbackState }.getOrNull()
            if (state != lastState) {
                log("playback-state", "playerId=" + System.identityHashCode(player) +
                    " old=" + lastState + " new=" + state + " trigger=" + trigger)
                lastState = state
            }
        }
    }
    private fun detach() {
        if (fragment != null) log("fragment-detach", source())
        tree?.let { tr -> onDraw?.let { if (tr.isAlive) tr.removeOnPreDrawListener(it) } }
        tree = null
        onDraw = null
        layoutListeners.forEach { (v, listener) -> v.removeOnLayoutChangeListener(listener) }
        layoutListeners.clear()
        unsubscribe?.invoke()
        unsubscribe = null
        fragment = null
        model = null
        observed = false
        lastPlayer = null
        lastState = null
        lastInstance = null
        lastDraw = 0L
        layouts = 0
    }
    override fun onActivityCreated(a: Activity, state: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityResumed(a: Activity) { (a as? MainActivity)?.let { bindHost(it) } }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {
        if (host === a) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycle)
            detach()
            host = null
        }
    }
}
