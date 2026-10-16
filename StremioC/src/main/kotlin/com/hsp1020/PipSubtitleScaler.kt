package com.hsp1020

import android.os.Build
import android.util.TypedValue
import android.view.View
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.annotation.OptIn
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import kotlin.math.roundToInt

/** Scale only StremioC playback captions with the actual PiP viewport, not a fixed PiP factor. */
@OptIn(UnstableApi::class)
internal class PipSubtitleScaler {
    private var fragment: Fragment? = null
    private var activity: FragmentActivity? = null
    private var subtitle: SubtitleView? = null
    private var normalHeight = 0
    private var normalTopPadding = 0
    private var normalTranslationY = 0f
    private var inPip = false

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        if (info.isInPictureInPictureMode) enterPip() else leavePip()
    }

    private val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val sub = view as? SubtitleView ?: return@OnLayoutChangeListener
        if (inPip) {
            scaleElevation(sub)
        } else if (sub.height > 0 && activity?.isInPictureInPictureMode != true) {
            normalHeight = sub.height
        } else if (activity?.isInPictureInPictureMode == true) {
            enterPip()
        }
    }

    fun attach(candidate: Fragment) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || candidate !is GeneratorPlayer) return
        val host = candidate.activity as? FragmentActivity ?: return
        val apiName = ViewModelProvider(candidate)[PlayerGeneratorViewModel::class.java]
            .state.generatorState?.response?.apiName ?: return
        if (apiName != "StremioC" && APIHolder.allProviders.none {
                it is StremioC && it.name.equals(apiName, ignoreCase = true)
            }) return
        val sub = candidate.subView ?: return
        if (fragment === candidate && subtitle === sub) return

        detach()
        fragment = candidate
        activity = host
        subtitle = sub
        normalHeight = sub.height
        sub.addOnLayoutChangeListener(layoutListener)
        host.addOnPictureInPictureModeChangedListener(pipListener)
        if (host.isInPictureInPictureMode) sub.post {
            if (subtitle === sub) enterPip()
        }
    }

    private fun captionSizeSp(): Float =
        (SubtitlesFragment.getCurrentSavedStyle().fixedTextSize ?: 25f).coerceAtLeast(1f)

    private fun enterPip() {
        val sub = subtitle ?: return
        if (inPip) return
        val height = normalHeight.takeIf { it > 0 } ?: sub.height.takeIf { it > 0 } ?: return
        normalHeight = height
        normalTopPadding = sub.paddingTop
        normalTranslationY = sub.translationY
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, captionSizeSp(), sub.resources.displayMetrics
        )
        // A fractional size also follows subsequent PiP window resizes automatically.
        sub.setFractionalTextSize((sizePx / height).coerceIn(0.001f, 1f), true)
        inPip = true
        scaleElevation(sub)
    }

    private fun scaleElevation(sub: SubtitleView) {
        if (normalHeight <= 0 || sub.height <= 0) return
        val ratio = (sub.height.toFloat() / normalHeight).coerceIn(0f, 1f)
        val top = (normalTopPadding * ratio).roundToInt()
        if (sub.paddingTop != top) {
            sub.setPadding(sub.paddingLeft, top, sub.paddingRight, sub.paddingBottom)
        }
        sub.translationY = normalTranslationY * ratio
    }

    private fun leavePip() {
        if (!inPip) return
        inPip = false
        subtitle?.let { sub ->
            sub.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, captionSizeSp())
            sub.setPadding(sub.paddingLeft, normalTopPadding, sub.paddingRight, sub.paddingBottom)
            sub.translationY = normalTranslationY
            if (sub.height > 0) normalHeight = sub.height
        }
    }

    fun detach(candidate: Fragment? = null) {
        if (candidate != null && fragment !== candidate) return
        leavePip()
        subtitle?.removeOnLayoutChangeListener(layoutListener)
        activity?.removeOnPictureInPictureModeChangedListener(pipListener)
        subtitle = null
        activity = null
        fragment = null
        normalHeight = 0
    }
}
