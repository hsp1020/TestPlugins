package com.hsp1020.elevate

import android.app.Dialog
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlin.math.roundToInt

/** Extend the existing native speed dialog only while Unified Player Panel is enabled. */
internal class ElevatePlaybackSpeedControl(
    private val fragment: GeneratorPlayer,
    private val root: View
) {
    private companion object {
        val dialogField by lazy {
            runCatching {
                FullScreenPlayer::class.java.getDeclaredField("selectSpeedDialog")
                    .apply { isAccessible = true }
            }.getOrNull()
        }
    }

    private var speedButton: View? = null
    private var activeDialog: java.lang.ref.WeakReference<Dialog>? = null

    private fun openNativeDialog(button: View) {
        if (!fragment.isAdded || fragment.view !== root) return
        button.isPressed = false
        // Keep the native dialog's pause/resume and back-button behavior.
        button.performClick()
        extendDialog()
    }

    private val touchListener = View.OnTouchListener { button, event ->
        if (event.actionMasked == MotionEvent.ACTION_UP &&
            button.isEnabled && button.isClickable &&
            event.x >= 0 && event.x < button.width &&
            event.y >= 0 && event.y < button.height) {
            openNativeDialog(button)
            true
        } else false
    }

    private val keyListener = View.OnKeyListener { button, keyCode, event ->
        val select = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_A -> true
            else -> false
        }
        if (event.action == KeyEvent.ACTION_UP && select) {
            openNativeDialog(button)
            true
        } else false
    }

    fun attach() {
        val button = root.findViewById<View>(R.id.player_speed_btt) ?: return
        if (speedButton === button) return
        detach()
        speedButton = button
        button.setOnTouchListener(touchListener)
        button.setOnKeyListener(keyListener)
    }

    fun detach() {
        speedButton?.setOnTouchListener(null)
        speedButton?.setOnKeyListener(null)
        speedButton = null
        activeDialog?.get()?.dismiss()
        activeDialog = null
    }

    fun restoreNativeLimit() {
        if (fragment.player.getPlaybackSpeed() > 2f) {
            fragment.player.setPlaybackSpeed(2f)
            DataStoreHelper.playBackSpeed = 2f
            updatePlayerLabel(2f)
        }
    }

    private fun updatePlayerLabel(speed: Float) {
        root.findViewById<TextView>(R.id.player_speed_btt)?.text =
            fragment.getString(R.string.player_speed_text_format)
                .format(speed).replace(".0x", "x")
    }

    private fun extendDialog() {
        val dialog = runCatching { dialogField?.get(fragment) as? Dialog }.getOrNull()
            ?.takeIf { it.isShowing } ?: return
        if (activeDialog?.get() === dialog) return
        val decor = dialog.window?.decorView ?: return
        val text = decor.findViewById<TextView>(R.id.speed_text) ?: return
        val slider = decor.findViewById<Slider>(R.id.speed_bar) ?: return
        val minus = decor.findViewById<View>(R.id.speed_minus) ?: return
        val plus = decor.findViewById<View>(R.id.speed_plus) ?: return
        val first = decor.findViewById<MaterialButton>(R.id.speed_25) ?: return
        val nativePresets = listOf(
            first,
            decor.findViewById<MaterialButton>(R.id.speed_100) ?: return,
            decor.findViewById<MaterialButton>(R.id.speed_125) ?: return,
            decor.findViewById<MaterialButton>(R.id.speed_150) ?: return,
            decor.findViewById<MaterialButton>(R.id.speed_200) ?: return
        )
        val row = first.parent as? LinearLayout ?: return
        val extra = MaterialButton(ContextThemeWrapper(first.context, R.style.BlackButton)).apply {
            id = View.generateViewId()
            this.text = "0.75x"
            setTextColor(first.textColors)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, first.textSize)
            backgroundTintList = first.backgroundTintList
            cornerRadius = first.cornerRadius
            minimumWidth = 0
            minWidth = 0
            setAllCaps(false)
        }
        row.addView(extra, 1, LinearLayout.LayoutParams(0, first.layoutParams.height, 1f))
        first.text = "0.5x"
        val presets = nativePresets.toMutableList().apply { add(1, extra) }
        presets.forEachIndexed { index, button ->
            button.minimumWidth = 0
            button.minWidth = 0
            button.nextFocusLeftId = presets[(index + presets.size - 1) % presets.size].id
            button.nextFocusRightId = presets[(index + 1) % presets.size].id
        }
        extra.nextFocusUpId = slider.id
        extra.nextFocusDownId = extra.id

        // Remove the native 2.0x-clamped slider handler before widening its range.
        slider.clearOnChangeListeners()
        slider.valueTo = 5f
        activeDialog = java.lang.ref.WeakReference(dialog)

        fun refresh() {
            val speed = fragment.player.getPlaybackSpeed().coerceIn(0.1f, 5f)
            text.text = "%.2fx".format(speed)
            val step = slider.stepSize
            val tick = ((speed / step).roundToInt() * step).coerceIn(0.1f, 5f)
            if (slider.value != tick) slider.value = tick
        }
        fun change(speed: Float) {
            val bounded = speed.coerceIn(0.1f, 5f)
            DataStoreHelper.playBackSpeed = bounded
            fragment.player.setPlaybackSpeed(bounded)
            updatePlayerLabel(bounded)
            refresh()
        }
        // Leave the four unchanged native presets wired to CloudStream.
        first.setOnClickListener { change(0.5f) }
        extra.setOnClickListener { change(0.75f) }
        minus.setOnClickListener { change(fragment.player.getPlaybackSpeed() - 0.1f) }
        plus.setOnClickListener { change(fragment.player.getPlaybackSpeed() + 0.1f) }
        slider.addOnChangeListener { _, value, fromUser -> if (fromUser) change(value) }
        refresh()
    }
}
