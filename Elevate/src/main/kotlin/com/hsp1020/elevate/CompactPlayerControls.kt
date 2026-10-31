package com.hsp1020.elevate

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

/** Restore all native button measurements when the feature is switched off. */
internal class CompactPlayerControls(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val hideNamesKey = application.getString(R.string.hide_player_control_names_key)
    private var host: FragmentActivity? = null
    private val panels = LinkedHashMap<GeneratorPlayer, Panel>()
    private val settingListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == hideNamesKey) main.post { panels.values.forEach { it.apply() } }
    }

    private val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, fragment: Fragment, view: View, state: Bundle?
        ) {
            if (fragment is GeneratorPlayer) view.post {
                if (fragment.view === view && host != null) watch(fragment, view)
            }
        }

        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (fragment is GeneratorPlayer) fragment.view?.let { watch(fragment, it) }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment is GeneratorPlayer) panels.remove(fragment)?.detach()
        }
    }

    fun start() {
        preferences.registerOnSharedPreferenceChangeListener(settingListener)
        application.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(settingListener)
        application.unregisterActivityLifecycleCallbacks(this)
        unbind()
    }

    private fun watch(fragment: GeneratorPlayer, root: View) {
        if (isLayout(TV or EMULATOR)) return
        if (panels[fragment]?.root === root) {
            panels[fragment]?.apply()
            return
        }
        panels.remove(fragment)?.detach()
        val scroll = root.findViewById<HorizontalScrollView>(R.id.player_controls_scroll) ?: return
        val strip = scroll.getChildAt(0) as? LinearLayout ?: return
        panels[fragment] = Panel(root, strip).also { it.attach() }
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        unbind()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragments, true)
        fun scan(manager: FragmentManager) {
            manager.fragments.forEach { fragment ->
                if (fragment is GeneratorPlayer) fragment.view?.let { watch(fragment, it) }
                if (fragment.isAdded) scan(fragment.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun unbind() {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragments)
        panels.values.forEach { it.detach() }
        panels.clear()
        host = null
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) use(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (host === activity) unbind()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private data class Size(
        val width: Int, val height: Int,
        val minWidth: Int, val minimumWidth: Int,
        val minHeight: Int, val minimumHeight: Int,
        val start: Int, val end: Int
    )

    private inner class Panel(val root: View, private val strip: LinearLayout) {
        private val originalTop = strip.paddingTop
        private val originalBottom = strip.paddingBottom
        private val saved = LinkedHashMap<MaterialButton, Size>()
        private var adjustedPadding = false
        // PiP additions change this strip's bounds; unrelated player layouts are ignored.
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            apply()
        }
        private fun dp(n: Int) = (n * strip.resources.displayMetrics.density + 0.5f).toInt()

        fun attach() {
            strip.addOnLayoutChangeListener(layoutListener)
            apply()
        }

        private fun buttons(): List<MaterialButton> = buildList {
            for (index in 0 until strip.childCount) {
                when (val child = strip.getChildAt(index)) {
                    is MaterialButton -> add(child)
                    is LinearLayout -> for (item in 0 until child.childCount) {
                        (child.getChildAt(item) as? MaterialButton)?.let(::add)
                    }
                }
            }
        }

        private fun snapshot(button: MaterialButton): Size {
            val lp = button.layoutParams as LinearLayout.LayoutParams
            return Size(lp.width, lp.height, button.minWidth, button.minimumWidth,
                button.minHeight, button.minimumHeight, lp.marginStart, lp.marginEnd)
        }

        private fun resize(button: MaterialButton, size: Size) {
            if (button.minWidth != size.minWidth) button.minWidth = size.minWidth
            if (button.minimumWidth != size.minimumWidth) button.minimumWidth = size.minimumWidth
            if (button.minHeight != size.minHeight) button.minHeight = size.minHeight
            if (button.minimumHeight != size.minimumHeight) button.minimumHeight = size.minimumHeight
            val lp = button.layoutParams as? LinearLayout.LayoutParams ?: return
            if (lp.width != size.width || lp.height != size.height ||
                lp.marginStart != size.start || lp.marginEnd != size.end) {
                lp.width = size.width
                lp.height = size.height
                lp.marginStart = size.start
                lp.marginEnd = size.end
                button.layoutParams = lp
            }
        }

        fun apply() {
            // CloudStream itself hides the labels by setting their textSize to zero.
            val sample = root.findViewById<MaterialButton>(R.id.player_resize_btt)
            val iconOnly = preferences.getBoolean(hideNamesKey, false) &&
                sample?.textSize == 0f
            if (!iconOnly) {
                restore()
                return
            }

            val edge = dp(6)
            val width = dp(54)
            val height = dp(48)
            val compact = Size(width, height, width, width, height, height, edge, edge)
            for (button in buttons()) {
                if (button !in saved) {
                    // PiP duplicates the resize button, which may already be compact.
                    // Restore it from that button's original dimensions instead.
                    val source = if (button.text?.toString() == "PIP") sample?.let { saved[it] } else null
                    saved[button] = source ?: snapshot(button)
                }
                resize(button, compact)
            }
            val vertical = dp(6)
            if (strip.paddingTop != vertical || strip.paddingBottom != vertical) {
                strip.setPadding(strip.paddingLeft, vertical, strip.paddingRight, vertical)
                adjustedPadding = true
            }
        }

        private fun restore() {
            if (saved.isEmpty() && !adjustedPadding) return
            // Restore detached panel controls too, before they return to the native row.
            saved.forEach { (button, size) -> resize(button, size) }
            saved.clear()
            if (adjustedPadding) {
                strip.setPadding(strip.paddingLeft, originalTop, strip.paddingRight, originalBottom)
                adjustedPadding = false
            }
        }

        fun detach() {
            strip.removeOnLayoutChangeListener(layoutListener)
            restore()
        }
    }
}
