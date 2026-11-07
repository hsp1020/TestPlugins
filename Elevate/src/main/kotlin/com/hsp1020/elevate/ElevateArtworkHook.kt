package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.result.ResultData
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.ui.result.ResultFragmentPhone
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

/**
 * Presentation-only hook. StremioC and other providers stay untouched:
 * StremioC can supply the optional multi-trailer and language flags through syncData.
 */
internal class ElevateArtworkHook(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private val details = mutableMapOf<Fragment, Detail>()
    private var homeRoot: View? = null
    private var homeList: RecyclerView? = null
    private var homeHero: PhoneHeroHook? = null
    private val homeChildren = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) { attachHomeHero() }
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    private fun attachHomeHero() {
        val parent = homeList ?: return
        val activity = host ?: return
        if (!isLayout(PHONE)) {
            homeHero?.detach()
            homeHero = null
            return
        }
        val hero = homeHero ?: PhoneHeroHook(activity) { _, _ -> null }.also { homeHero = it }
        runCatching { hero.attach(parent) }
    }
    private fun bindHome(root: View) {
        if (homeRoot === root) { attachHomeHero(); return }
        unbindHome()
        homeRoot = root
        val list = root.findViewById<RecyclerView>(R.id.home_master_recycler) ?: return
        homeList = list
        list.addOnChildAttachStateChangeListener(homeChildren)
        attachHomeHero()
        list.post { if (homeList === list) attachHomeHero() }
    }
    private fun unbindHome() {
        homeHero?.detach()
        homeHero = null
        homeList?.removeOnChildAttachStateChangeListener(homeChildren)
        homeList = null
        homeRoot = null
    }

    private inner class Detail(private val fragment: Fragment, private val root: View) {
        private val model = ViewModelProvider(fragment)[ResultViewModel2::class.java]
        private fun page(): ResultData? =
            (model.page.value as? Resource.Success<ResultData>)?.value
        private val backdrop = DetailBackdropLayout(root,
            requiresTitleForLogo = { page()?.syncData?.get("logo_foreign_language") == "true" },
            hasExpectedLogo = {
                val data = page()
                data?.syncData?.get("logo_expected") == "true" || !data?.logoUrl.isNullOrBlank()
            }
        )
        private val trailers = TrailerChooser(fragment, root, fragment.arguments?.getString("url"))
        private val pageObserver = Observer<Resource<ResultData>?> {
            root.post { if (fragment.view === root) restoreNativeArtwork() }
        }
        private val nativeTrailerObserver =
            Observer<List<com.lagradost.cloudstream3.ui.result.ExtractedTrailerData>> {
                restoreNativeArtwork()
                if (it.any { item -> item.mirros.isNotEmpty() }) {
                    (fragment as? ResultFragmentPhone)?.player?.onPause()
                }
            }
        private var lastRestoredLogo: String? = null
        private fun find(name: String): View? {
            val id = root.resources.getIdentifier(name, "id", root.context.packageName)
            return if (id == 0) null else root.findViewById(id)
        }
        private fun restoreNativeArtwork() {
            if (fragment.view !== root || !isLayout(PHONE)) return
            val nativeTrailers = model.trailers.value.orEmpty()
            if (nativeTrailers.none { it.mirros.isNotEmpty() }) return
            val holder = find("result_poster_background_holder")
            holder?.clearAnimation()
            if (holder?.visibility != View.VISIBLE) holder?.visibility = View.VISIBLE
            if (holder?.alpha != 1f) holder?.alpha = 1f
            find("result_smallscreen_holder")?.visibility = View.INVISIBLE

            // Native ResultFragmentPhone.loadTrailer() deliberately calls bindLogo(null).
            // Restore the actual metadata logo once, after the native trailer observer.
            val data = page() ?: return
            val logoUrl = data.logoUrl?.takeIf { it.isNotBlank() }
            val logoView = find("background_poster_watermark_badge") as? ImageView
            val titleView = find("result_title") as? TextView
            if (logoView != null && titleView != null && logoUrl != null &&
                (lastRestoredLogo != logoUrl || logoView.visibility != View.VISIBLE)) {
                lastRestoredLogo = logoUrl
                ResultFragment.bindLogo(
                    logoUrl, data.posterHeaders, logoView, titleView
                )
            }
        }
        fun start() {
            runCatching { backdrop.start() }
            runCatching { trailers.start() }
            model.page.observe(fragment.viewLifecycleOwner, pageObserver)
            model.trailers.observe(fragment.viewLifecycleOwner, nativeTrailerObserver)
            restoreNativeArtwork()
        }
        fun stop(restoreNative: Boolean = false) {
            model.page.removeObserver(pageObserver)
            model.trailers.removeObserver(nativeTrailerObserver)
            trailers.stop()
            backdrop.stop()
            // Let the native detail fragment manage trailer UI again after disabling.
            if (restoreNative && fragment.view === root && isLayout(PHONE)) {
                runCatching {
                    val method = ResultFragmentPhone::class.java.getDeclaredMethod("setTrailers", List::class.java)
                        .apply { isAccessible = true }
                    method.invoke(fragment, model.trailers.value.orEmpty().flatMap { it.mirros })
                }
            }
        }
    }

    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            when (fragment.javaClass.name) {
                HOME -> bindHome(view)
                in DETAIL_CLASSES -> bindDetail(fragment, view)
            }
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (homeRoot === fragment.view || fragment.javaClass.name == HOME && homeRoot != null) unbindHome()
            details.remove(fragment)?.stop()
        }
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (fragment.javaClass.name == HOME) fragment.view?.let(::bindHome)
        }
    }
    private fun bindDetail(fragment: Fragment, view: View) {
        if (details.containsKey(fragment)) return
        runCatching { Detail(fragment, view).also { it.start() } }
            .onSuccess { details[fragment] = it }
    }
    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        detach()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                fragment.view?.let { view ->
                    if (fragment.javaClass.name == HOME) bindHome(view)
                    else if (fragment.javaClass.name in DETAIL_CLASSES) bindDetail(fragment, view)
                }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }
    private fun detach() {
        unbindHome()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        details.values.toList().forEach { it.stop(restoreNative = true) }
        details.clear()
        host = null
    }
    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }
    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        detach()
    }
    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) detach()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val HOME = "com.lagradost.cloudstream3.ui.home.HomeFragment"
        val DETAIL_CLASSES = setOf(
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
        )
    }
}
