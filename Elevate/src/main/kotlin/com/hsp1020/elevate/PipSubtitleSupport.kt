package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.isPIPPossible
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment

@OptIn(UnstableApi::class)
class PipSubtitleScaler(private val acceptsProvider: (String) -> Boolean) {
    private var watched: Fragment? = null
    private var removeObserver: (() -> Unit)? = null
    private var attached: Fragment? = null
    private var activity: FragmentActivity? = null
    private var subtitle: SubtitleView? = null
    private var inPip = false
    private var pipPositionObserver: ViewTreeObserver? = null
    private val pipPositionGuard = ViewTreeObserver.OnPreDrawListener {

        if (inPip) subtitle?.let { view ->
            if (view.translationY != 0f) view.translationY = 0f
        }
        true
    }

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { event ->
        if (event.isInPictureInPictureMode) enterPip() else leavePip()
    }
    private val subtitleStyleListener: (SaveCaptionStyle) -> Unit = {

        val view = subtitle
        view?.post {
            if (inPip && subtitle === view) applyPipStyle(view)
        }
    }

    fun watch(candidate: Fragment) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || candidate !is GeneratorPlayer) return
        if (watched !== candidate) {
            detach()
            watched = candidate
            val model = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]

            val observer = Observer<VideoLive<Resource<Unit>>> { bind(candidate) }
            model.loadingLinks.observe(candidate.viewLifecycleOwner, observer)
            removeObserver = { model.loadingLinks.removeObserver(observer) }
        }
        bind(candidate)
    }

    private fun bind(candidate: Fragment) {
        if (watched !== candidate || !candidate.isAdded) return
        val playerFragment = candidate as? GeneratorPlayer ?: return
        val host = playerFragment.activity as? FragmentActivity ?: return
        val name = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
            .state.generatorState?.response?.apiName ?: return
        if (!acceptsProvider(name)) {
            unbind()
            return
        }
        val view = playerFragment.subView ?: return
        if (attached === candidate && subtitle === view) return
        unbind()
        attached = candidate
        activity = host
        subtitle = view
        host.addOnPictureInPictureModeChangedListener(pipListener)
        SubtitlesFragment.applyStyleEvent += subtitleStyleListener
        if (host.isInPictureInPictureMode) enterPip()
    }

    private fun applyPipStyle(view: SubtitleView) {

        if (view.paddingTop != 0) {
            view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
        }
        if (view.translationY != 0f) view.translationY = 0f
        view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.5f)
    }

    private fun startPositionGuard(view: SubtitleView) {
        stopPositionGuard()
        view.viewTreeObserver.takeIf { it.isAlive }?.let { observer ->
            observer.addOnPreDrawListener(pipPositionGuard)
            pipPositionObserver = observer
        }
    }

    private fun stopPositionGuard() {
        pipPositionObserver?.let { observer ->
            if (observer.isAlive) observer.removeOnPreDrawListener(pipPositionGuard)
        }
        pipPositionObserver = null
    }

    private fun enterPip() {
        val view = subtitle ?: return
        if (inPip) return
        inPip = true
        applyPipStyle(view)
        startPositionGuard(view)
    }

    private fun leavePip() {
        if (!inPip) return
        inPip = false
        stopPositionGuard()
        subtitle?.let { view ->
            val style = SubtitlesFragment.getCurrentSavedStyle()

            SubtitlesFragment.setSubtitleViewStyle(view, style, true)
            view.translationY = -TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                style.elevation.toFloat(),
                view.resources.displayMetrics
            )
        }
    }

    private fun unbind() {
        leavePip()
        stopPositionGuard()
        activity?.removeOnPictureInPictureModeChangedListener(pipListener)
        if (subtitle != null) SubtitlesFragment.applyStyleEvent -= subtitleStyleListener
        attached = null
        activity = null
        subtitle = null
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && watched !== candidate && attached !== candidate) return
        removeObserver?.invoke()
        removeObserver = null
        watched = null
        unbind()
    }
}

private class PipControlButton {
    private var fragment: GeneratorPlayer? = null
    private var root: View? = null
    private var button: MaterialButton? = null
    private var left: MaterialButton? = null
    private var right: MaterialButton? = null
    private var originalLeftNext = View.NO_ID
    private var originalRightPrevious = View.NO_ID
    private var preferences: SharedPreferences? = null

    private val settingListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val context = root?.context
        if (context != null && (key == context.getString(R.string.pip_enabled_key) ||
            key == context.getString(R.string.hide_player_control_names_key))) {
            root?.post { refresh() }
        }
    }

    fun watch(candidate: Fragment) {
        if (candidate !is GeneratorPlayer || !candidate.isAdded) return
        val view = candidate.view ?: return
        if (fragment !== candidate || root !== view) {
            detach()
            fragment = candidate
            root = view
            preferences = PreferenceManager.getDefaultSharedPreferences(view.context).also {
                it.registerOnSharedPreferenceChangeListener(settingListener)
            }
        }
        refresh()
        if (button == null) {
            view.post {
                if (fragment === candidate && root === candidate.view) refresh()
            }
        }
    }

    private fun refresh() {
        val player = fragment ?: return
        val view = root ?: return
        if (!player.isAdded || player.view !== view) return
        val activity = player.activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !activity.isPIPPossible()) {
            removeButton()
            return
        }
        val scroll = view.findViewById<View>(R.id.player_controls_scroll) ?: return
        if (scroll.visibility == View.GONE) {
            removeButton()
            return
        }
        val holder = view.findViewById<LinearLayout>(R.id.player_lock_holder) ?: return
        if (button?.parent !== holder) {
            removeButton()
            val sample = holder.findViewById<MaterialButton>(R.id.player_resize_btt)
                ?.takeIf { it.parent === holder } ?: return
            val following = holder.findViewById<MaterialButton>(R.id.player_speed_btt)
                ?.takeIf { it.parent === holder }
            val iconId = view.resources.getIdentifier(
                "pip_24px", "drawable", view.context.packageName
            ).takeIf { it != 0 } ?: R.drawable.ic_baseline_picture_in_picture_alt_24

            val control = MaterialButton(view.context).apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(sample.layoutParams as LinearLayout.LayoutParams)
                gravity = sample.gravity
                minWidth = sample.minWidth
                minHeight = sample.minHeight
                setPadding(sample.paddingLeft, sample.paddingTop, sample.paddingRight, sample.paddingBottom)
                backgroundTintList = sample.backgroundTintList
                rippleColor = sample.rippleColor
                strokeColor = sample.strokeColor
                strokeWidth = sample.strokeWidth
                cornerRadius = sample.cornerRadius
                icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, iconId)
                iconTint = sample.iconTint
                iconSize = sample.iconSize
                iconPadding = sample.iconPadding
                iconGravity = sample.iconGravity
                setTextAppearance(
                    view.context,
                    if (sample.tag == view.context.getString(R.string.tv_no_focus_tag)) {
                        R.style.VideoButtonTV
                    } else {
                        R.style.VideoButton
                    }
                )
                setTextColor(sample.textColors)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sample.textSize)
                typeface = sample.typeface
                letterSpacing = sample.letterSpacing
                textScaleX = sample.textScaleX
                includeFontPadding = sample.includeFontPadding
                isAllCaps = false
                stateListAnimator = sample.stateListAnimator
                isFocusable = sample.isFocusable
                contentDescription = "PIP"
                text = "PIP"
                nextFocusUpId = sample.nextFocusUpId
                nextFocusDownId = sample.nextFocusDownId
                setOnClickListener {
                    val current = fragment ?: return@setOnClickListener
                    val host = current.activity ?: return@setOnClickListener
                    if (!current.isResumed || current.view !== root || !host.isPIPPossible()) {
                        return@setOnClickListener
                    }
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            @Suppress("DEPRECATION")
                            host.enterPictureInPictureMode()
                        }
                    }
                }
            }
            originalLeftNext = sample.nextFocusRightId
            originalRightPrevious = following?.nextFocusLeftId ?: View.NO_ID
            val index = following?.let(holder::indexOfChild) ?: (holder.indexOfChild(sample) + 1)
            holder.addView(control, index)
            left = sample
            right = following
            sample.nextFocusRightId = control.id
            following?.nextFocusLeftId = control.id
            control.nextFocusLeftId = sample.id
            control.nextFocusRightId = following?.id ?: originalLeftNext
            button = control
        }

        val hideName = preferences?.getBoolean(
            view.context.getString(R.string.hide_player_control_names_key), false
        ) == true
        button?.apply {
            if (hideName) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 0f)
                iconPadding = 0
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                setPadding(0, 0, 0, 0)
            } else {
                val sample = this@PipControlButton.left ?: return@apply
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sample.textSize)
                iconPadding = sample.iconPadding
                iconGravity = sample.iconGravity
                setPadding(sample.paddingLeft, sample.paddingTop, sample.paddingRight, sample.paddingBottom)
            }
        }
    }

    private fun removeButton() {
        val control = button ?: return
        left?.takeIf { it.nextFocusRightId == control.id }?.nextFocusRightId = originalLeftNext
        right?.takeIf { it.nextFocusLeftId == control.id }?.nextFocusLeftId = originalRightPrevious
        (control.parent as? LinearLayout)?.removeView(control)
        control.setOnClickListener(null)
        button = null
        left = null
        right = null
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && candidate !== fragment) return
        preferences?.unregisterOnSharedPreferenceChangeListener(settingListener)
        preferences = null
        removeButton()
        fragment = null
        root = null
    }
}

class PipSubtitleLifecycle(
    private val application: Application,
    acceptsProvider: (String) -> Boolean,
    private val controlsEnabled: () -> Boolean
) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val scaler = PipSubtitleScaler(acceptsProvider)
    private val controls = PipControlButton()
    private var host: FragmentActivity? = null
    private var running = false

    private fun watch(fragment: Fragment) {
        if (fragment !is GeneratorPlayer || fragment.view == null) return
        scaler.watch(fragment)
        if (controlsEnabled()) controls.watch(fragment) else controls.detach(fragment)
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) = watch(f)

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = watch(f)

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            scaler.detach(f)
            controls.detach(f)
        }
    }

    fun start() {
        main.post {
            if (running) return@post
            running = true
            application.registerActivityLifecycleCallbacks(this)
            (CommonActivity.activity as? MainActivity)?.let(::use)
        }
    }

    fun stop() {
        main.post {
            if (!running) return@post
            running = false
            scaler.detach()
            controls.detach()
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            host = null
            application.unregisterActivityLifecycleCallbacks(this)
        }
    }

    fun refresh() {
        main.post {
            if (!running) return@post
            val activity = host ?: return@post
            if (!controlsEnabled()) controls.detach()
            fun scan(fm: FragmentManager) {
                fm.fragments.forEach { fragment ->
                    if (fragment.view != null) watch(fragment)
                    if (fragment.isAdded) scan(fragment.childFragmentManager)
                }
            }
            scan(activity.supportFragmentManager)
        }
    }

    private fun use(activity: FragmentActivity) {
        if (!running || host === activity) return
        scaler.detach()
        controls.detach()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        refresh()
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            scaler.detach()
            controls.detach()
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            host = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
