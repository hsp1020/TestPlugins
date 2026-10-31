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
    private val activities = LinkedHashSet<FragmentActivity>()
    private val screens = LinkedHashMap<Fragment, Watch>()

    // Weak keys avoid retaining recycled RecyclerViews. A signature change
    // invalidates prior inspection even if a listener was replaced in place.
    private val checked = java.util.WeakHashMap<RecyclerView, Long>()
    private val listenerField by lazy {
        runCatching {
            RecyclerView::class.java.getDeclaredField("mScrollListeners").apply {
                isAccessible = true
            }
        }.getOrNull()
    }

    private fun signature(list: List<RecyclerView.OnScrollListener>): Long {
        var result = -3750763034362895579L
        for (listener in list)
            result = (result xor System.identityHashCode(listener).toLong()) *
                1099511628211L
        return result xor list.size.toLong()
    }

    private fun prune(recycler: RecyclerView, owner: String, callSite: String) {
        val field = listenerField ?: return
        val current = runCatching {
            @Suppress("UNCHECKED_CAST")
            field.get(recycler) as? List<RecyclerView.OnScrollListener>
        }.getOrNull()
        if (current == null) {
            checked.remove(recycler)
            return
        }
        val before = signature(current)
        if (checked[recycler] == before) return
        var previous: RecyclerView.OnScrollListener? = null
        var obsolete: ArrayList<RecyclerView.OnScrollListener>? = null
        for (listener in current) {
            val name = listener.javaClass.name
            if (!name.startsWith(owner) || !name.contains(callSite)) continue
            if (previous != null) {
                if (obsolete == null) obsolete = ArrayList(2)
                obsolete.add(previous)
            }
            previous = listener
        }
        if (obsolete.isNullOrEmpty()) {
            checked[recycler] = before
            return
        }
        obsolete.forEach(recycler::removeOnScrollListener)
        val remaining = runCatching {
            @Suppress("UNCHECKED_CAST")
            field.get(recycler) as? List<RecyclerView.OnScrollListener>
        }.getOrNull()
        if (remaining == null) checked.remove(recycler)
        else checked[recycler] = signature(remaining)
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
        if (!activities.add(next)) return
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

    private fun unbind(activity: FragmentActivity? = null) {
        val targets = activity?.let(::listOf) ?: activities.toList()
        targets.forEach { it.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragments) }
        activities.removeAll(targets.toSet())
        val removed = screens.filter { (fragment, _) -> activity == null || fragment.activity === activity }
        removed.forEach { (fragment, screen) -> screen.stop(); screens.remove(fragment) }
        if (activities.isEmpty()) checked.clear()
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is MainActivity || PipLibraryNavigation.isPlayerActivity(activity)) {
            (activity as? FragmentActivity)?.let(::use)
        }
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity || PipLibraryNavigation.isPlayerActivity(activity)) {
            (activity as? FragmentActivity)?.let(::use)
        }
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (activity is FragmentActivity && activity in activities) unbind(activity)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
