package com.hsp1020.pip

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
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
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
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

class PipSubtitleLifecycle(
    private val application: Application,
    acceptsProvider: (String) -> Boolean
) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val scaler = PipSubtitleScaler(acceptsProvider)
    private var host: FragmentActivity? = null
    private var running = false

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            scaler.watch(f)
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            scaler.watch(f)
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            scaler.detach(f)
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
            host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
            host = null
            application.unregisterActivityLifecycleCallbacks(this)
        }
    }

    private fun use(activity: FragmentActivity) {
        if (!running || host === activity) return
        scaler.detach()
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { fragment ->
                if (fragment.view != null) scaler.watch(fragment)
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) {
            scaler.detach()
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
