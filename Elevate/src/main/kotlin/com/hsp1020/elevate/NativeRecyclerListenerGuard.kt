package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private val checks = java.util.WeakHashMap<RecyclerView, Long>()
    private val identified = mutableSetOf<String>()
    private val readFailures = mutableSetOf<String>()
    private val removals = mutableMapOf<String, Int>()

    private val listenerField by lazy {
        val resolved = runCatching {
            RecyclerView::class.java.getDeclaredField("mScrollListeners").apply {
                isAccessible = true
            }
        }
        Log.i(LOG_TAG, "stage=reflection success=" + (resolved.getOrNull() != null) +
            " reason=" + (resolved.exceptionOrNull()?.javaClass?.simpleName ?: "none"))
        resolved.getOrNull()
    }

    // Cheap, allocation-free identity fingerprint of ALL listeners, so
    // replacement in the middle of an unchanged-size list is also detected.
    private fun fingerprint(list: List<RecyclerView.OnScrollListener>): Long {
        var signature = -3750763034362895579L
        for (listener in list) {
            signature = (signature xor System.identityHashCode(listener).toLong()) *
                1099511628211L
        }
        return signature xor list.size.toLong()
    }

    private fun prune(
        recycler: RecyclerView, owner: String, callSite: String, screen: String
    ) {
        val field = listenerField ?: return
        val listeners = try {
            @Suppress("UNCHECKED_CAST")
            field.get(recycler) as? List<RecyclerView.OnScrollListener>
        } catch (failure: Throwable) {
            if (readFailures.add(screen))
                Log.w(LOG_TAG, "stage=read screen=" + screen +
                    " success=false reason=" + failure.javaClass.simpleName)
            return
        }
        if (listeners == null) {
            // RecyclerView may not have initialized its listener list yet.
            if (identified.add(screen + ":uninitialized"))
                Log.i(LOG_TAG, "stage=read screen=" + screen + " status=uninitialized")
            checks.remove(recycler)
            return
        }
        val signature = fingerprint(listeners)
        if (checks[recycler] == signature) return

        var latest: RecyclerView.OnScrollListener? = null
        var matched = 0
        var sample: String? = null
        // Only allocate stale references when the native call site really
        // emitted duplicates; never copy the whole listener list per layout.
        var stale: ArrayList<RecyclerView.OnScrollListener>? = null
        for (listener in listeners) {
            val type = listener.javaClass.name
            if (type.startsWith(owner) && type.contains(callSite)) {
                if (latest != null) {
                    if (stale == null) stale = ArrayList(2)
                    stale.add(latest)
                }
                latest = listener
                matched++
                if (sample == null) sample = type
            }
        }
        val state = if (matched > 0) "match" else "no-match"
        if (identified.add(screen + ":" + state))
            Log.i(LOG_TAG, "stage=identify screen=" + screen + " status=" + state +
                " before=" + listeners.size + " matched=" + matched +
                " class=" + (sample ?: listeners.firstOrNull()?.javaClass?.name ?: "none"))

        if (stale.isNullOrEmpty()) {
            checks[recycler] = signature
            return
        }
        val before = listeners.size
        val obsolete = stale ?: return
        obsolete.forEach(recycler::removeOnScrollListener)
        val remaining = try {
            @Suppress("UNCHECKED_CAST")
            field.get(recycler) as? List<RecyclerView.OnScrollListener>
        } catch (_: Throwable) {
            null
        }
        if (remaining == null) checks.remove(recycler)
        else checks[recycler] = fingerprint(remaining)

        val prior = removals[screen] ?: 0
        val total = prior + obsolete.size
        removals[screen] = total
        if (prior < 12) {
            val stillNative = remaining?.count {
                val name = it.javaClass.name
                name.startsWith(owner) && name.contains(callSite)
            } ?: -1
            Log.i(LOG_TAG, "stage=pruned screen=" + screen + " before=" + before +
                " matched=" + matched + " removed=" + obsolete.size +
                " after=" + (remaining?.size ?: -1) +
                " remainingNative=" + stillNative + " totalRemoved=" + total)
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
                        "onBindContent", "home")
                }
            } else if (player) {
                root.findViewById<RecyclerView>(R.id.player_episode_list)?.let {
                    prune(it,
                        "com.lagradost.cloudstream3.ui.player.GeneratorPlayer" + "$",
                        "showEpisodesOverlay", "player")
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
        val screen = if (name.endsWith("HomeFragment")) "home" else "player"
        if (identified.add(screen + ":attached"))
            Log.i(LOG_TAG, "stage=attached screen=" + screen)
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
        if (removals.isNotEmpty())
            Log.i(LOG_TAG, "stage=summary homeRemoved=" + (removals["home"] ?: 0) +
                " playerRemoved=" + (removals["player"] ?: 0))
        removals.clear()
        identified.clear()
        readFailures.clear()
        checks.clear()
        activity = null
    }

    private companion object {
        const val LOG_TAG = "Elevate-RecyclerGuard"
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
