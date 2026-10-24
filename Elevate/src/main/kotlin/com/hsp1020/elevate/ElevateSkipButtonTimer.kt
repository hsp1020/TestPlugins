package com.hsp1020.elevate

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel

/**
 * Nuvio-style timeout indicator on the existing CloudStream skip button.
 *
 * The core calls onTimestamp(...) to show this button, then schedules a 6-second hide.
 * The first native width expansion is an event we can observe without polling frames.
 * Invalidating its public skipIndex cancels only the pending native hide; a 10-second
 * countdown takes over, without replacing the button, click handling or D-pad focus.
 */
internal class ElevateSkipButtonTimer(
    private val fragment: GeneratorPlayer,
    private val model: PlayerGeneratorViewModel
) {
    private val main = Handler(Looper.getMainLooper())
    private var button: View? = null
    private var indicator: FrameLayout? = null
    private var fill: View? = null
    private var active = false
    private var elapsedMs = 0L
    private var lastTickMs = 0L
    private var intervalKey: String? = null
    private var restoring = false

    private fun dp(value: Float): Int =
        (value * (button?.resources?.displayMetrics?.density ?: 1f) + 0.5f).toInt()

    private val buttonLayout = View.OnLayoutChangeListener {
            view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (restoring) return@OnLayoutChangeListener
        val width = right - left
        val oldWidth = oldRight - oldLeft
        if (view.visibility != View.VISIBLE) {
            if (active) reset()
            return@OnLayoutChangeListener
        }
        if (active && width + dp(2f) < oldWidth) {
            // Core genuinely hid the button (manual skip, end of interval, etc).
            reset()
        } else if (!active && width > oldWidth + dp(1f)) {
            begin()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val view = button
            if (view == null || view.visibility != View.VISIBLE || fragment.view == null) {
                reset()
                return
            }
            val now = SystemClock.uptimeMillis()
            val controlsVisible = fragment.isUIShowing()
            if (!controlsVisible) elapsedMs += (now - lastTickMs).coerceAtLeast(0L)
            lastTickMs = now
            val fraction = (elapsedMs / HIDE_TIMEOUT_MS.toFloat()).coerceIn(0f, 1f)
            indicator?.alpha = if (controlsVisible) 0f else 1f
            fill?.scaleX = fraction

            val current = currentIntervalKey()
            if (current != null && intervalKey != current) {
                intervalKey = current
                elapsedMs = 0L
                fill?.scaleX = 0f
                // Every new skip region gets a fresh ten-second window.
                fragment.skipIndex++
            } else if (current == null && intervalKey != null) {
                // Native timestamp is no longer applicable.
                reset()
                fragment.onTimestamp(null)
                return
            }

            if (elapsedMs >= HIDE_TIMEOUT_MS && !controlsVisible) {
                reset()
                // Use CloudStream's own hide path; it also restores skip focus/state.
                fragment.onTimestamp(null)
                return
            }
            main.postDelayed(this, UPDATE_MS)
        }
    }

    private fun currentIntervalKey(): String? {
        val position = fragment.playerView?.player?.currentPosition ?: return null
        if (position < 0L) return null
        return model.state.stamps.firstOrNull {
            val stamp = it.timestamp
            position >= stamp.startMs && position < stamp.endMs
        }?.let {
            val stamp = it.timestamp
            "${stamp.type}:${stamp.startMs}:${stamp.endMs}"
        }
    }

    fun attach() {
        val host = fragment.playerView ?: return
        val next = host.findViewById<View>(R.id.skip_chapter_button) ?: return
        if (next === button) return
        dispose()
        val parent = next.parent as? ConstraintLayout ?: return
        button = next

        val strip = FrameLayout(next.context).apply {
            id = View.generateViewId()
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            elevation = next.elevation + dp(2f)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(2f).toFloat()
            }
            clipToOutline = true
        }
        strip.addView(View(next.context).apply {
            setBackgroundColor(Color.argb(38, 255, 255, 255))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val progress = View(next.context).apply {
            pivotX = 0f
            scaleX = 0f
            setBackgroundColor(Color.argb(217, 30, 30, 30))
        }
        strip.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val params = ConstraintLayout.LayoutParams(0, dp(3f)).apply {
            startToStart = next.id
            endToEnd = next.id
            bottomToBottom = next.id
        }
        parent.addView(strip, params)
        indicator = strip
        fill = progress
        next.addOnLayoutChangeListener(buttonLayout)
    }

    private fun begin() {
        if (active || button?.visibility != View.VISIBLE) return
        active = true
        elapsedMs = 0L
        intervalKey = currentIntervalKey()
        lastTickMs = SystemClock.uptimeMillis()
        indicator?.visibility = View.VISIBLE
        fill?.scaleX = 0f
        // The core's six-second runnable compares this with its captured index.
        // It must not preempt Nuvio's ten-second progress indicator.
        fragment.skipIndex++
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun reset() {
        active = false
        main.removeCallbacks(tick)
        indicator?.visibility = View.GONE
        elapsedMs = 0L
        intervalKey = null
    }

    fun dispose() {
        reset()
        val previous = button
        previous?.removeOnLayoutChangeListener(buttonLayout)
        (indicator?.parent as? ViewGroup)?.removeView(indicator)
        button = null
        indicator = null
        fill = null
    }

    companion object {
        private const val HIDE_TIMEOUT_MS = 10_000L
        private const val UPDATE_MS = 125L
    }
}
