package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R

/**
 * Release stale *native* listeners created by CloudStream's repeated Home and
 * player episode overlay binds. This only removes older instances from the
 * same native call site, leaving the newest callback and every other listener
 * untouched. Uses the public removeOnScrollListener API for removal.
 *
 * RecyclerView listener enumeration is private in androidx.recyclerview; if
 * that field changes, fail closed (do nothing). No per-frame listener, layout
 * interception, adapter replacement, or player resource interception.
 */
internal class NativeRecyclerListenerGuard(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var activity: FragmentActivity? = null
    private val screens = LinkedHashMap<Fragment, Watch>()

    private val listenerField by lazy {
        runCatching {
            RecyclerView::class.java.getDeclaredField("mScrollListeners").apply {
                isAccessible = true
            }
        }.getOrNull()
    }

    private fun prune(recycler: RecyclerView, owner: String, callSite: String) {
        val field = listenerField ?: return
        val snapshot = runCatching {
            @Suppress("UNCHECKED_CAST")
            (field.get(recycler) as? List<RecyclerView.OnScrollListener>)?.toList()
        }.getOrNull() ?: return
        // Other listeners (pagination, focus, touch behavior) are never touched.
        val duplicates = snapshot.filter {
            val name = it.javaClass.name
            name.startsWith(owner) && name.contains(callSite)
        }
        if (duplicates.size < 2) return
        // Native bind always appends a newly captured callback. Only the latest
        // one references the current Home row / current episode list.
        for (old in duplicates.dropLast(1)) {
            recycler.removeOnScrollListener(old)
        }
    }

    private inner class Watch(private val fragment: Fragment, val root: View) {
        private val tree = root.viewTreeObserver
        private val home = fragment.javaClass.name ==
            "com.lagradost.cloudstream3.ui.home.HomeFragment"
        private val player = fragment.javaClass.name ==
            "com.lagradost.cloudstream3.ui.player.GeneratorPlayer"
        private var queued = false
        private val observer = ViewTreeObserver.OnGlobalLayoutListener { schedule() }
        private val work = Runnable {
            queued = false
            if (!fragment.isAdded || fragment.view !== root) return@Runnable
            if (home) {
                val master = root.findViewById<RecyclerView>(R.id.home_master_recycler)
                    ?: return@Runnable
                for (i in 0 until master.childCount) {
                    val child = master.getChildAt(i)
                        .findViewById<RecyclerView>(R.id.home_child_recyclerview) ?: continue
                    prune(child,
                        "com.lagradost.cloudstream3.ui.home.ParentItemAdapter" + "$",
                        "onBindContent")
                }
            } else if (player) {
                root.findViewById<RecyclerView>(R.id.player_episode_list)?.let {
                    prune(it,
                        "com.lagradost.cloudstream3.ui.player.GeneratorPlayer" + "$",
                        "showEpisodesOverlay")
                }
            }
        }

        fun start() {
            if (tree.isAlive) tree.addOnGlobalLayoutListener(observer)
            schedule()
        }
        private fun schedule() {
            if (queued) return
            queued = true
            // Group expensive RecyclerView rebind bursts. Nothing is disabled:
            // cleanup can wait until layout stabilizes.
            main.postDelayed(work, 120L)
        }
        fun stop() {
            if (tree.isAlive) tree.removeOnGlobalLayoutListener(observer)
            main.removeCallbacks(work)
            queued = false
        }
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, v: View, saved: Bundle?
        ) = attach(fragment, v)

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            screens.remove(fragment)?.stop()
        }
    }

    private fun attach(fragment: Fragment, view: View) {
        val name = fragment.javaClass.name
        if (name != "com.lagradost.cloudstream3.ui.home.HomeFragment" &&
            name != "com.lagradost.cloudstream3.ui.player.GeneratorPlayer") return
        val old = screens[fragment]
        if (old?.root === view) return
        old?.stop()
        screens[fragment] = Watch(fragment, view).also { it.start() }
    }

    private fun use(next: FragmentActivity) {
        if (activity === next) return
        unbind()
        activity = next
        next.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { f ->
                f.view?.let { attach(f, it) }
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(next.supportFragmentManager)
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        application.unregisterActivityLifecycleCallbacks(this)
        unbind()
    }

    private fun unbind() {
        activity?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        screens.values.forEach { it.stop() }
        screens.clear()
        activity = null
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity === activity) unbind()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
