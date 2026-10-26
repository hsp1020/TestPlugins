package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R

internal class HomeHeroToggle(private val app: Application) :
    Application.ActivityLifecycleCallbacks {
    private var host: FragmentActivity? = null
    private var home: Fragment? = null
    private var list: RecyclerView? = null
    private var binding: Hero? = null
    private var loadingBanner: View? = null
    private var loadingBannerVisibility = View.VISIBLE
    private var hideHero = false
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { scan() }
    private val childListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) { scan() }
        override fun onChildViewDetachedFromWindow(view: View) {
            if (binding?.root === view) {
                binding?.release()
                binding = null
            }
        }
    }
    private val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, view: View, saved: Bundle?
        ) {
            if (f.javaClass.name == HOME_CLASS) bindHome(f, view)
        }
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === home) unbindHome()
        }
    }

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        unbindHome()
        host = null
    }

    fun setHidden(value: Boolean) {
        if (hideHero == value) return
        hideHero = value
        loadingBanner?.let { banner ->
            val visibility = if (value) View.GONE else loadingBannerVisibility
            if (banner.visibility != visibility) banner.visibility = visibility
        }
        binding?.apply(!value)
        scan()
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
        unbindHome()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(callbacks, true)
        fun scanFragments(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                if (f.javaClass.name == HOME_CLASS) f.view?.let { bindHome(f, it) }
                if (f.isAdded) scanFragments(f.childFragmentManager)
            }
        }
        scanFragments(activity.supportFragmentManager)
    }

    private fun bindHome(fragment: Fragment, root: View) {
        val next = root.findViewById<RecyclerView>(R.id.home_master_recycler) ?: return
        if (home === fragment && list === next) return
        unbindHome()
        home = fragment
        list = next
        // Native phone/TV loading layouts have the hero placeholder followed by
        // three loading_list rows. Collapse only that first placeholder.
        val shimmerContent = (root.findViewById<ViewGroup>(R.id.home_loading_shimmer)
            ?.getChildAt(0) as? ViewGroup)
        loadingBanner = shimmerContent
            ?.takeIf { it.childCount == 2 && it.getChildAt(1) is ViewGroup }
            ?.getChildAt(0)
        loadingBannerVisibility = loadingBanner?.visibility ?: View.VISIBLE
        if (hideHero) loadingBanner?.visibility = View.GONE
        next.addOnChildAttachStateChangeListener(childListener)
        next.viewTreeObserver.takeIf { it.isAlive }?.addOnGlobalLayoutListener(layout)
        scan()
    }

    private fun unbindHome() {
        binding?.release()
        binding = null
        loadingBanner?.let { banner ->
            if (banner.visibility != loadingBannerVisibility) banner.visibility = loadingBannerVisibility
        }
        loadingBanner = null
        list?.removeOnChildAttachStateChangeListener(childListener)
        list?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout)
        list = null
        home = null
    }

    private fun scan() {
        val recycler = list ?: return
        val header = (0 until recycler.childCount).asSequence()
            .map(recycler::getChildAt)
            .firstOrNull { it.findViewById<View>(R.id.home_header) != null } ?: return
        if (binding?.root !== header) {
            binding?.release()
            binding = Hero(header)
        }
        binding?.apply(!hideHero)
    }

    private inner class Hero(val root: View) {
        private val mobileHolder = root.findViewById<View>(R.id.home_preview_viewpager_text)
            ?.takeIf { root.findViewById<View>(R.id.home_preview_title_holder) != null }
        private val toolbar = mobileHolder?.findViewById<View>(R.id.home_padding)
        private val pager = root.findViewById<View>(R.id.home_preview_viewpager)
        private val buttons = root.findViewById<View>(R.id.home_preview_title_holder)
        private val tvCard = root.findViewById<View>(R.id.home_preview_info_btt)
        private val tvFrame = tvCard?.parent?.parent as? View
        private val mobileHeight = mobileHolder?.layoutParams?.height
        private val originalTvVisibility = tvFrame?.visibility
        private val watchList = root.findViewById<View>(R.id.home_watch_child_recyclerview)
        private val originalNextFocusUp = watchList?.nextFocusUpId
        private val tree = root.viewTreeObserver
        private var hidden = false
        private var lastPagerVisibility = View.VISIBLE
        private var lastButtonsVisibility = View.VISIBLE
        // An occasional native visibility overwrite must be corrected BEFORE
        // draw to prevent a one-frame hero flash. The hot-path guard checks
        // only properties; actual height recomputation runs on layout changes.
        private var enforceScheduled = false
        private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
            if (hidden && (
                (mobileHolder != null &&
                    (pager?.visibility != View.GONE || buttons?.visibility != View.GONE)) ||
                (tvFrame != null &&
                    (tvFrame.visibility != View.GONE || tvFrame.hasFocus()))
            )) enforceHidden()
            true
        }
        private val onLayout = ViewTreeObserver.OnGlobalLayoutListener {
            if (!hidden || enforceScheduled) return@OnGlobalLayoutListener
            enforceScheduled = true
            root.post {
                enforceScheduled = false
                if (hidden) enforceHidden()
            }
        }

        init {
            if (tree.isAlive) {
                tree.addOnPreDrawListener(beforeDraw)
                tree.addOnGlobalLayoutListener(onLayout)
            }
        }

        fun apply(visible: Boolean) {
            if (visible) {
                if (!hidden) return
                hidden = false
                mobileHolder?.let { holder ->
                    mobileHeight?.let { height ->
                        if (holder.layoutParams.height != height) {
                            holder.layoutParams = holder.layoutParams.apply { this.height = height }
                        }
                    }
                    pager?.visibility = lastPagerVisibility
                    buttons?.visibility = lastButtonsVisibility
                }
                tvFrame?.visibility = originalTvVisibility ?: View.VISIBLE
                originalNextFocusUp?.let { watchList?.nextFocusUpId = it }
            } else {
                if (!hidden) {
                    lastPagerVisibility = pager?.visibility ?: View.VISIBLE
                    lastButtonsVisibility = buttons?.visibility ?: View.VISIBLE
                }
                hidden = true
                enforceHidden()
            }
        }

        private fun enforceHidden() {
            if (mobileHolder != null) {
                val density = root.resources.displayMetrics.density
                val gap = (4 * density + 0.5f).toInt()
                val controls = toolbar
                val margins = controls?.layoutParams as? ViewGroup.MarginLayoutParams
                val controlHeight = controls?.height?.takeIf { it > 0 }
                    ?: controls?.layoutParams?.height?.takeIf { it > 0 }
                    ?: (50 * density + 0.5f).toInt()
                val controlTop = maxOf(controls?.top ?: 0, margins?.topMargin ?: 0)
                val requiredHeight = controlTop + controlHeight + gap
                if (mobileHolder.layoutParams.height != requiredHeight) {
                    mobileHolder.layoutParams = mobileHolder.layoutParams.apply { height = requiredHeight }
                }
                if (pager?.visibility != View.GONE) pager?.visibility = View.GONE
                if (buttons?.visibility != View.GONE) buttons?.visibility = View.GONE
            } else if (tvFrame != null) {
                if (tvFrame.hasFocus()) {
                    val target = host?.findViewById<View>(R.id.home_change_api)
                    if (target?.isShown == true) target.requestFocus()
                }
                if (tvFrame.visibility != View.GONE) tvFrame.visibility = View.GONE
                watchList?.nextFocusUpId = R.id.home_change_api
            }
        }

        fun release() {
            if (tree.isAlive) {
                tree.removeOnPreDrawListener(beforeDraw)
                tree.removeOnGlobalLayoutListener(onLayout)
            }
            apply(true)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(callbacks)
            unbindHome()
            host = null
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private companion object {
        const val HOME_CLASS = "com.lagradost.cloudstream3.ui.home.HomeFragment"
    }
}
