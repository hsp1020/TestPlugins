package com.hsp1020.elevate

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.core.view.doOnPreDraw
import com.facebook.shimmer.Shimmer
import com.facebook.shimmer.ShimmerFrameLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute

internal class ElevateActorLoading(context: Context, rows: Int = 1) : FrameLayout(context) {
    private val shimmer = ShimmerFrameLayout(context).apply {
        setShimmer(Shimmer.AlphaHighlightBuilder()
            .setBaseAlpha(0.2f).setHighlightAlpha(0.3f).setDuration(2000L).build())
    }

    init {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        repeat(rows) {
            val placeholder = LayoutInflater.from(context).inflate(R.layout.loading_list, list, false)
            val fill = context.colorFromAttribute(R.attr.textColor)
            fun tint(view: View) {
                if (view is CardView) view.setCardBackgroundColor(fill)
                else if (view !is ViewGroup && view.background != null) view.setBackgroundColor(fill)
                if (view is ViewGroup) for (i in 0 until view.childCount) tint(view.getChildAt(i))
            }
            tint(placeholder)
            list.addView(placeholder)
        }
        shimmer.addView(list, LayoutParams(-1, -2))
        addView(shimmer, LayoutParams(-1, -1))
        isFocusable = false
        isClickable = false
        visibility = View.GONE
    }

    fun loading(active: Boolean) {
        visibility = if (active) View.VISIBLE else View.GONE
        if (active && isAttachedToWindow) shimmer.startShimmer()
        else if (!active) shimmer.stopShimmer()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) shimmer.startShimmer()
    }

    override fun onDetachedFromWindow() {
        shimmer.stopShimmer()
        super.onDetachedFromWindow()
    }
}


internal fun elevateActorDestinationReady(activity: FragmentActivity, destination: View): Boolean {
    val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.view
        ?: return false
    val bounds = host.parent as? ViewGroup ?: return false
    return host.visibility == View.VISIBLE &&
        host.left <= bounds.paddingLeft && host.top <= bounds.paddingTop &&
        host.right >= bounds.width - bounds.paddingRight &&
        host.bottom >= bounds.height - bounds.paddingBottom &&
        !host.isLayoutRequested && destination.isShown &&
        destination.width > 0 && destination.height > 0 &&
        !destination.isLayoutRequested && destination.animation?.hasEnded() != false
}

internal fun elevateActorCurrentDestinationView(
    activity: FragmentActivity, navigation: NavController?
): View? {
    val navHost = activity.supportFragmentManager
        .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return null
    val primary = navHost.childFragmentManager.primaryNavigationFragment ?: return null
    val id = navigation?.currentDestination?.id ?: return null
    val type = primary.javaClass.name
    if (id == R.id.navigation_home &&
        type != "com.lagradost.cloudstream3.ui.home.HomeFragment") return null
    if (id == R.id.navigation_results_phone &&
        type !in setOf(
            "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
            "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone"
        )) return null
    if (id == R.id.navigation_results_tv &&
        type != "com.lagradost.cloudstream3.ui.result.ResultFragmentTv") return null
    if (id == R.id.navigation_player &&
        type != "com.lagradost.cloudstream3.ui.player.GeneratorPlayer") return null
    return primary.view
}

internal class ElevateActorHandoff(
    private val activity: FragmentActivity,
    private val dialog: AppCompatDialog,
    private val navigation: NavController?,
    private val onLeave: () -> Unit,
    private val onReturn: () -> Unit = { dialog.show() },
    private val onReady: () -> Unit = {},
    private val onReturnStart: () -> Unit = {},
    private val preserveNestedNavigation: Boolean = false
) {
    val isActive get() = returnEntryId != null

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var observing = false
    private var returning = false
    private var recovering = false
    private var recoveringHome = false
    private var recoveryAttempts = 0
    private var returnStarted = 0L
    private var returnTargetId: String? = null
    private var returnToOrigin = true
    private var returnEntryId: String? = null
    private var detailEntryId: String? = null
    private var pendingDetailDraw = false
    private var detailRevealEnd: (() -> Unit)? = null

    private fun currentDestinationView(): View? =
        elevateActorCurrentDestinationView(activity, navigation)

    private fun finishReturn(ticket: Int) {
        if (ticket != generation || !isActive || !returning ||
            navigation?.currentBackStackEntry?.id != returnTargetId) return
        val view = currentDestinationView() ?: return
        if (!elevateActorDestinationReady(activity, view)) return
        val toOrigin = returnToOrigin
        close()
        if (toOrigin) onReturn() else onLeave()
    }

    private fun watchReturnFrame(view: View) {
        if (!returning || !isActive || detailRevealEnd != null ||
            !view.isAttachedToWindow) return
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        val ticket = generation
        var stable = 0
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var release: () -> Unit
        release = {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            if (detailRevealEnd === release) detailRevealEnd = null
        }
        listener = ViewTreeObserver.OnPreDrawListener {
            if (ticket != generation || !returning || !isActive ||
                navigation?.currentBackStackEntry?.id != returnTargetId) {
                release()
            } else {
                stable = if (currentDestinationView() === view &&
                    elevateActorDestinationReady(activity, view)) stable + 1 else 0
                if (stable >= 2) {
                    release()
                    view.postOnAnimation { finishReturn(ticket) }
                } else view.postInvalidateOnAnimation()
            }
            true
        }
        detailRevealEnd = release
        observer.addOnPreDrawListener(listener)
        view.postInvalidateOnAnimation()
    }

    private fun scheduleReturn(target: String, toOrigin: Boolean = true) {
        if (!isActive || (returning && returnTargetId == target)) return
        detailRevealEnd?.invoke()
        returning = true
        pendingDetailDraw = false
        returnTargetId = target
        returnToOrigin = toOrigin
        returnStarted = SystemClock.uptimeMillis()
        handler.removeCallbacks(navigationTimeout)
        handler.removeCallbacks(returnTimeout)
        if (toOrigin) onReturnStart()
        currentDestinationView()?.let(::watchReturnFrame)
        handler.postDelayed(returnTimeout, 1000L)
    }

    private fun recoverHome(): Boolean {
        if (!isActive || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
        val nav = navigation ?: return false
        recoveringHome = true
        val moved = runCatching {
            nav.navigate(R.id.global_to_navigation_home)
            nav.currentDestination?.id == R.id.navigation_home
        }.getOrDefault(false)
        val homeEntry = nav.currentBackStackEntry?.id
        if (moved && homeEntry != null) {
            scheduleReturn(homeEntry, false)
            return true
        }
        recoveringHome = false
        return false
    }

    private val navigationTimeout = object : Runnable {
        override fun run() {
            if (!isActive || !pendingDetailDraw) return
            if (activity.isFinishing || activity.isDestroyed) {
                close()
                onLeave()
                return
            }
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                handler.postDelayed(this, 1000L)
                return
            }
            val nav = navigation
            val origin = returnEntryId
            if (origin != null && nav?.currentBackStackEntry?.id == origin) {
                scheduleReturn(origin)
                return
            }
            if (origin != null && detailEntryId == nav?.currentBackStackEntry?.id) {
                recovering = true
                val popped = runCatching { nav?.popBackStack() == true }.getOrDefault(false)
                recovering = false
                if (popped && nav?.currentBackStackEntry?.id == origin) {
                    scheduleReturn(origin)
                    return
                }
            }
            recoveryAttempts++

            if (recoveryAttempts >= 2 && recoverHome()) return
            handler.postDelayed(this, 1000L)
        }
    }
    private val returnTimeout = object : Runnable {
        override fun run() {
            if (!isActive || !returning) return
            if (activity.isFinishing || activity.isDestroyed) {
                close()
                onLeave()
                return
            }
            if (navigation?.currentBackStackEntry?.id == returnTargetId &&
                detailRevealEnd == null) currentDestinationView()?.let(::watchReturnFrame)
            if (returnToOrigin && SystemClock.uptimeMillis() - returnStarted >= 6000L) {
                if (recoverHome()) return
            }

            handler.postDelayed(this, 1000L)
        }
    }
    private val activityObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY && isActive) {
            close()
            onLeave()
        }
    }

    fun begin(): Boolean {
        if (isActive || activity.isFinishing || activity.isDestroyed) return false
        val origin = navigation?.currentBackStackEntry?.id ?: return false
        generation++
        returnEntryId = origin
        detailEntryId = null
        pendingDetailDraw = true
        returning = false
        recovering = false
        recoveringHome = false
        recoveryAttempts = 0
        observing = true
        navigation.addOnDestinationChangedListener(destinationListener)
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(detailViewListener, true)
        activity.lifecycle.addObserver(activityObserver)
        handler.postDelayed(navigationTimeout, 6000L)
        return true
    }

    fun close() {
        generation++
        handler.removeCallbacks(navigationTimeout)
        handler.removeCallbacks(returnTimeout)
        returnEntryId = null
        detailEntryId = null
        returnTargetId = null
        pendingDetailDraw = false
        returning = false
        recovering = false
        recoveringHome = false
        detailRevealEnd?.invoke()
        detailRevealEnd = null
        if (observing) {
            observing = false
            navigation?.removeOnDestinationChangedListener(destinationListener)
            activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(detailViewListener)
            activity.lifecycle.removeObserver(activityObserver)
        }
    }

    private fun revealDetailWhenReady(detail: View) {
        detailRevealEnd?.invoke()
        val ticket = generation
        detail.doOnPreDraw {
            if (ticket != generation || !isActive || !pendingDetailDraw) return@doOnPreDraw
            val observer = detail.viewTreeObserver
            var stable = 0
            var pendingReveal: Runnable? = null
            lateinit var listener: ViewTreeObserver.OnPreDrawListener
            lateinit var release: () -> Unit
            release = {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                pendingReveal?.let(detail::removeCallbacks)
                pendingReveal = null
                if (detailRevealEnd === release) detailRevealEnd = null
            }
            listener = ViewTreeObserver.OnPreDrawListener {
                if (ticket != generation || !isActive || !pendingDetailDraw ||
                    returnEntryId == null || detailEntryId != navigation?.currentBackStackEntry?.id) {
                    release()
                } else if (pendingReveal == null) {
                    val ready = currentDestinationView() === detail &&
                        elevateActorDestinationReady(activity, detail)
                    stable = if (ready) stable + 1 else 0
                    if (stable >= 3) {
                        val reveal = Runnable {
                            pendingReveal = null
                            if (ticket != generation || !isActive || !pendingDetailDraw ||
                                returnEntryId == null ||
                                detailEntryId != navigation?.currentBackStackEntry?.id) {
                                release()
                            } else if (currentDestinationView() === detail &&
                                elevateActorDestinationReady(activity, detail)) {
                                release()
                                pendingDetailDraw = false
                                handler.removeCallbacks(navigationTimeout)
                                dialog.hide()
                                onReady()
                            } else {
                                // A late native layout pass invalidated the final check.
                                // Re-arm the original readiness check instead of getting stuck.
                                stable = 0
                                detail.postInvalidateOnAnimation()
                            }
                        }
                        pendingReveal = reveal
                        detail.postOnAnimation(reveal)
                    } else detail.postInvalidateOnAnimation()
                }
                true
            }
            detailRevealEnd = release
            observer.addOnPreDrawListener(listener)
            detail.postInvalidateOnAnimation()
        }
    }

    private val detailViewListener = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
        ) {
            if (returning) {
                val ticket = generation
                view.doOnPreDraw {
                    if (ticket == generation && returning &&
                        navigation?.currentBackStackEntry?.id == returnTargetId &&
                        currentDestinationView() === view) watchReturnFrame(view)
                }
                return
            }
            if (!preserveNestedNavigation && returnEntryId != null &&
                fragment.javaClass.name == "com.lagradost.cloudstream3.ui.search.SearchFragment") {
                close()
                onLeave()
                return
            }
            if (!pendingDetailDraw || !isActive) return
            if (fragment.javaClass.name !in setOf(
                    "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
                    "com.lagradost.cloudstream3.ui.result.ResultFragmentTv",
                    "com.lagradost.cloudstream3.ui.result.ResultTrailerPlayer",
                    "com.lagradost.cloudstream3.ui.search.SearchFragment"
                )) return
            revealDetailWhenReady(view)
        }
    }
    private val destinationListener = NavController.OnDestinationChangedListener { controller, _, _ ->
        val origin = returnEntryId
        if (isActive && origin != null) {
            val current = controller.currentBackStackEntry?.id
            when {
                current == origin -> {
                    if (detailEntryId != null) scheduleReturn(origin)
                }
                recoveringHome && controller.currentDestination?.id == R.id.navigation_home -> {
                    if (current != null) scheduleReturn(current, false)
                }
                recovering -> Unit
                detailEntryId == null -> detailEntryId = current
                current != detailEntryId && preserveNestedNavigation &&
                    controller.currentDestination?.id in setOf(
                        R.id.navigation_search, R.id.navigation_quick_search,
                        R.id.navigation_results_phone, R.id.navigation_results_tv,
                        R.id.navigation_player
                    ) -> {
                    if (pendingDetailDraw) {
                        pendingDetailDraw = false
                        handler.removeCallbacks(navigationTimeout)
                        dialog.hide()
                        onReady()
                    }
                }
                current != detailEntryId -> {
                    close()
                    onLeave()
                }
            }
        }
    }
}


internal fun elevateActorPressedForeground(view: View): Drawable? {
    val out = TypedValue()
    return if (view.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, out, true
        ) && out.resourceId != 0)
        AppCompatResources.getDrawable(view.context, out.resourceId)?.mutate()
    else null
}

internal fun elevateActorOutlinedForeground(view: View, pressed: Drawable? = elevateActorPressedForeground(view)): Drawable? {
    if (!isLayout(TV)) return pressed
    val outline = AppCompatResources.getDrawable(view.context, R.drawable.outline_drawable)?.mutate()
    return when {
        pressed != null && outline != null ->
            android.graphics.drawable.LayerDrawable(arrayOf(pressed, outline))
        else -> outline ?: pressed
    }
}


