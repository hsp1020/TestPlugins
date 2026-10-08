package com.hsp1020

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import kotlin.math.roundToInt

/** Only replaces known MDBList glyphs on rows marked by StremioC.kt. */
internal class RatingPlotImageHook(
    context: Context,
    private val resources: Resources
) : Application.ActivityLifecycleCallbacks {
    private val app = context.applicationContext as Application
    private val prefs = app.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private fun trace(message: String) { android.util.Log.i("StremioCRating", message) }
    private fun traceError(stage: String, error: Throwable) {
        android.util.Log.e("StremioCRating", "$stage failed", error)
    }
    private var host: FragmentActivity? = null
    private val watchers = mutableMapOf<Fragment, Watcher>()
    private val ids = mutableMapOf<String, Int>()
    private val icons = linkedMapOf(
        "Ⓜ️" to "rating_mal", "⭐" to "rating_imdb",
        "🟢" to "rating_letterboxd", "Ⓣ" to "rating_trakt",
        "🎬" to "rating_tmdb", "🎯" to "rating_metacritic",
        "🍅\u2060\u2060" to "rating_rotten_tomatoes_certified",
        "🍅\u2060" to "rating_rotten_tomatoes_rotten",
        "🍅" to "rating_rotten_tomatoes",
        "🍿\u2060\u2060" to "rating_audience_verified_hot",
        "🍿\u2060" to "rating_audience_stale",
        "🍿" to "rating_audience_score"
    )
    private val number = Regex("^\\s+\\d+(?:\\.\\d+)?(?:\\s|$)")
    private class LogoSpan(d: android.graphics.drawable.Drawable) : ImageSpan(d, ImageSpan.ALIGN_BOTTOM)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mdblist_enabled") main.post {
            trace("PREF enabled=${prefs.getBoolean("mdblist_enabled", false)} watchers=${watchers.size}")
            watchers.values.toList().forEach { runCatching { it.render() }.onFailure { e -> traceError("PREF_RENDER", e) } }
        }
    }
    private val fragmentCallback = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?
        ) {
            if (f.javaClass.name.contains("ResultFragment")) trace("FRAGMENT viewCreated=${f.javaClass.name}")
            runCatching { attach(f, v) }.onFailure { traceError("ATTACH", it) }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            watchers.remove(f)?.let { trace("FRAGMENT watcherDetached=${f.javaClass.simpleName}"); it.detach() }
        }
    }

    fun start() {
        trace("HOOK start enabled=${prefs.getBoolean("mdblist_enabled", false)} activity=${CommonActivity.activity?.javaClass?.simpleName ?: "null"} resourcePkg=${BuildConfig.LIBRARY_PACKAGE_NAME}")
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        app.registerActivityLifecycleCallbacks(this)
        (CommonActivity.activity as? MainActivity)?.let(::use)
    }

    fun stop() {
        trace("HOOK stop watchers=${watchers.size}")
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        app.unregisterActivityLifecycleCallbacks(this)
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = null
    }

    private fun use(activity: FragmentActivity) {
        if (host === activity) return
        trace("ACTIVITY registered=${activity.javaClass.name} fragments=${activity.supportFragmentManager.fragments.size}")
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear()
        host = activity
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallback, true)
        fun scan(fm: FragmentManager) {
            fm.fragments.forEach { f ->
                f.view?.let { attach(f, it) }
                if (f.isAdded) scan(f.childFragmentManager)
            }
        }
        scan(activity.supportFragmentManager)
    }

    private fun attach(f: Fragment, root: View) {
        val className = f.javaClass.name
        if (!className.contains("ResultFragment")) return
        if (watchers.containsKey(f)) { trace("ATTACH skip=already-watching class=$className"); return }
        if (className !in setOf(
                "com.lagradost.cloudstream3.ui.result.ResultFragmentPhone",
                "com.lagradost.cloudstream3.ui.result.ResultFragmentTv"
            )) { trace("ATTACH skip=fragment-class class=$className"); return }
        val apiName = f.arguments?.getString("apiName")
        if (apiName == null) { trace("ATTACH skip=missing-apiName class=$className"); return }
        if (apiName != "StremioC" && !APIHolder.allProviders.any {
                it is StremioC && it.name.equals(apiName, ignoreCase = true)
            }) { trace("ATTACH skip=provider apiName=$apiName"); return }
        val resId = root.resources.getIdentifier("result_description", "id", root.context.packageName)
        if (resId == 0) { trace("ATTACH skip=textview-id-0 hostPkg=${root.context.packageName}"); return }
        val view = root.findViewById<TextView>(resId)
        if (view == null) { trace("ATTACH skip=textview-not-found id=$resId class=$className"); return }
        trace("ATTACH success apiName=$apiName textViewId=$resId")
        watchers[f] = Watcher(view).also { it.start() }
    }

    private inner class Watcher(private val view: TextView) : TextWatcher {
        private var changing = false
        fun start() {
            view.addTextChangedListener(this)
            trace("WATCH start len=${view.text?.length ?: -1}")
            runCatching { render() }.onFailure { traceError("WATCH_START_RENDER", it) }
        }
        fun detach() { view.removeTextChangedListener(this); runCatching { restore() }.onFailure { traceError("WATCH_RESTORE", it) } }
        private fun restore() {
            val old = view.text as? Spanned ?: return
            val spans = old.getSpans(0, old.length, LogoSpan::class.java)
            if (spans.isEmpty()) return
            val plain = SpannableStringBuilder(old)
            spans.forEach(plain::removeSpan)
            changing = true
            try { view.setText(plain, TextView.BufferType.SPANNABLE) }
            finally { changing = false }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (!changing) runCatching { render() }.onFailure { traceError("TEXT_CHANGED_RENDER", it) }
        }

        fun render() {
            if (!prefs.getBoolean("mdblist_enabled", false)) { trace("RENDER skip=mdblist-disabled"); restore(); return }
            val original = view.text
            if (original == null) { trace("RENDER skip=no-text"); return }
            if (original is Spanned && original.getSpans(0, original.length, LogoSpan::class.java).isNotEmpty()) {
                trace("RENDER skip=already-applied"); return
            }
            // Invisible separator U+2063 is inserted ONLY for actual MDBList rows.
            if (!original.startsWith('\u2063')) {
                trace("RENDER skip=no-marker length=${original.length} firstCode=${original.firstOrNull()?.code ?: -1}")
                return
            }
            trace("RENDER marker-found length=${original.length}")
            val end = original.indexOf('\n').let { if (it < 0) original.length else it }
            val line = original.subSequence(0, end).toString()
            val replacements = mutableListOf<Triple<Int, Int, String>>()
            for ((glyph, file) in icons) {
                var from = 1
                while (true) {
                    val at = line.indexOf(glyph, from)
                    if (at < 0) break
                    if (number.containsMatchIn(line.substring(at + glyph.length))) {
                        replacements.add(Triple(at, at + glyph.length, file))
                    }
                    from = at + glyph.length
                }
            }
            if (replacements.isEmpty()) { trace("RENDER skip=no-glyph-match"); return }
            trace("RENDER matched=${replacements.size} files=${replacements.map { it.third }.distinct().joinToString()}")
            val result = SpannableStringBuilder(original)
            var applied = 0
            val height = (view.textSize * 0.92f).roundToInt().coerceAtLeast(12)
            for ((start, endIndex, file) in replacements) {
                val id = ids.getOrPut(file) {
                    resources.getIdentifier(file, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
                }
                if (id == 0) { trace("RESOURCE missing=$file package=${BuildConfig.LIBRARY_PACKAGE_NAME}"); continue }
                val image = runCatching { resources.getDrawable(id, null).mutate() }
                    .onFailure { traceError("RESOURCE_DECODE file=$file id=$id", it) }
                    .getOrNull()
                if (image == null) continue
                trace("RESOURCE loaded=$file id=$id size=${image.intrinsicWidth}x${image.intrinsicHeight}")
                val width = (height.toFloat() * image.intrinsicWidth.coerceAtLeast(1) /
                    image.intrinsicHeight.coerceAtLeast(1)).roundToInt().coerceIn(height / 2, height * 3)
                image.setBounds(0, 0, width, height)
                result.setSpan(LogoSpan(image), start, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                applied++
            }
            if (applied == 0) { trace("RENDER skip=zero-spans count=${replacements.size}"); return }
            changing = true
            try { view.setText(result, TextView.BufferType.SPANNABLE) }
            finally { changing = false }
            trace("RENDER success spans=$applied")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is MainActivity) runCatching { use(activity) }.onFailure { traceError("ACTIVITY_RESUME", it) }
    }
    override fun onActivityDestroyed(activity: Activity) { if (host === activity) {
        host?.supportFragmentManager?.unregisterFragmentLifecycleCallbacks(fragmentCallback)
        watchers.values.toList().forEach { it.detach() }
        watchers.clear(); host = null
    } }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
