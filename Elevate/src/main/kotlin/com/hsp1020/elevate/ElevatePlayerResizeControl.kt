package com.hsp1020.elevate

import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerView
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlin.math.roundToInt

/** Reuses CloudStream's resize cycle and Media3's native Stretch (Fill) mode. */
@OptIn(UnstableApi::class)
internal class ElevatePlayerResizeControl(
    private val fragment: GeneratorPlayer,
    private val root: View,
    private val host: () -> PlayerView?
) {
    private var mode = -1
    private var button: View? = null
    private var videoView: androidx.media3.ui.PlayerView? = null
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (mode >= 3 && (r - l != oldR - oldL || b - t != oldB - oldT)) apply()
    }
    private val touchListener = View.OnTouchListener { view, event ->
        if (event.actionMasked == MotionEvent.ACTION_UP && view.isEnabled && view.isClickable &&
            event.x >= 0 && event.x < view.width && event.y >= 0 && event.y < view.height
        ) next(view) else false
    }
    private val keyListener = View.OnKeyListener { view, key, event ->
        val select = when (key) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_A -> true
            else -> false
        }
        if (select && event.action == KeyEvent.ACTION_UP) next(view) else false
    }

    fun attach() {
        val target = root.findViewById<View>(R.id.player_resize_btt) ?: return
        if (target === button) return
        detach()
        mode = (host()?.resizeMode ?: DataStoreHelper.resizeMode).coerceIn(0, 2)
        button = target
        target.setOnTouchListener(touchListener)
        target.setOnKeyListener(keyListener)
        onVideoChanged()
    }

    fun onVideoChanged() {
        val current = fragment.playerView
        if (videoView !== current) {
            videoView?.removeOnLayoutChangeListener(layoutListener)
            videoView = current
            current?.addOnLayoutChangeListener(layoutListener)
        }
        if (mode >= 3) apply()
    }

    fun nextFromShortcut(): Boolean = button?.let(::next) ?: false

    private fun next(target: View): Boolean {
        if (!fragment.isAdded || fragment.view !== root) return false
        target.isPressed = false
        when (mode) {
            0, 1 -> {
                target.performClick() // Preserve native mode, toast, and preference.
                mode = (host()?.resizeMode ?: DataStoreHelper.resizeMode).coerceIn(0, 2)
            }
            6 -> {
                restoreNative()
                target.performClick() // Native Zoom -> Fit.
                mode = (host()?.resizeMode ?: DataStoreHelper.resizeMode).coerceIn(0, 2)
            }
            else -> {
                mode++ // Zoom -> 16:9 -> 4:3 -> 21:9 -> Original.
                host()?.gestureHelper?.clearZoomState()
                apply()
                host()?.scheduleAutoHide()
                val label = when (mode) {
                    3 -> "16:9"
                    4 -> "4:3"
                    5 -> "21:9"
                    else -> "Original" // CloudStream has no native original-size string.
                }
                Toast.makeText(target.context, label, Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun apply() {
        val view = videoView ?: return
        val content = view.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        ) ?: return
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return
        val (targetW, targetH) = if (mode == 6) {
            val size = view.player?.videoSize ?: return
            if (size.width <= 0 || size.height <= 0) return
            (size.width * size.pixelWidthHeightRatio).roundToInt().coerceAtLeast(1) to size.height
        } else {
            val ratio = when (mode) {
                3 -> 16f / 9f
                4 -> 4f / 3f
                else -> 21f / 9f
            }
            if (width.toFloat() / height > ratio)
                (height * ratio).roundToInt() to height
            else width to (width / ratio).roundToInt()
        }
        val params = content.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width != targetW || params.height != targetH || params.gravity != Gravity.CENTER) {
            content.layoutParams = FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER)
        }
        // Identical stretching behavior to the native Fill mode; only bounds differ.
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    private fun restoreNative() {
        val view = videoView ?: return
        val content = view.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        ) ?: return
        content.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        )
        view.resizeMode = when (host()?.resizeMode ?: DataStoreHelper.resizeMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun detach() {
        button?.setOnTouchListener(null)
        button?.setOnKeyListener(null)
        button = null
        if (mode >= 3) restoreNative()
        videoView?.removeOnLayoutChangeListener(layoutListener)
        videoView = null
        mode = -1
    }
}
