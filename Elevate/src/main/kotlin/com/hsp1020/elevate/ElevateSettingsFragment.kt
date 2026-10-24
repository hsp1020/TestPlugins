package com.hsp1020.elevate

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment

internal enum class ElevateFeature(
    val section: String,
    val label: String,
    val description: String
) {
    DETAIL_SEARCH("Navigation & Search", "Native Detail Search",
        "Open full CloudStream search from the title details and retain back navigation."),
    PROVIDER_ORDER("Navigation & Search", "Provider Ordering",
        "Use saved provider order on Home and Search, including related search providers."),
    HERO_CAROUSEL("Home & Library", "Hide Home Hero Carousel",
        "Hide the featured Home banner while keeping search and profile controls available."),
    HOME_REPAIR("Home & Library", "Home Poster Refresh",
        "Repair stale Home cards when switching providers."),
    LIBRARY_POSTERS("Home & Library", "Custom Library Posters",
        "Replace library covers via RPDB, XRDB, OPDB, AIORatings, TOP Posters or EasyRatingsDB."),
    EPISODE_PROGRESS("Home & Library", "Episode Progress Indicators",
        "Show watched progress on large episode thumbnails."),
    EMBEDDED_SUBTITLES("Playback & Subtitles", "Embedded Subtitle Management",
        "Prefer matching embedded subtitles and discard stale tracks when videos change."),
    AUDIO_TRACKS("Playback & Subtitles", "Automatic Audio Language",
        "Select app-language audio, then Japanese for anime or English for other titles."),
    SKIP("Playback & Subtitles", "Unified Skip Intervals",
        "Combine native timestamps with AniSkip, Anime Skip, IntroDb and TheIntroDB."),
    PIP_BUTTON("Playback & Subtitles", "Picture-in-Picture Button",
        "Show a native-style PiP player button when PiP is enabled in CloudStream."),
    PIP_SUBTITLES("Playback & Subtitles", "PiP Subtitle Styling",
        "Adjust subtitle scale and placement while in Picture-in-Picture."),
    SUBTITLE_RETRY("Playback & Subtitles", "Subtitle Referer Retry",
        "Retry blocked subtitle requests with the current video's Referer when needed.")
}

internal object ElevateFeaturePrefs {
    const val FILE = "ElevateFeatures"
    fun defaultEnabled(feature: ElevateFeature): Boolean =
        feature != ElevateFeature.HERO_CAROUSEL && feature != ElevateFeature.LIBRARY_POSTERS
    fun enabled(context: Context, feature: ElevateFeature): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(feature.name, defaultEnabled(feature))
}

class ElevateSettingsFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        fun dp(value: Int): Int = (value * ctx.resources.displayMetrics.density + 0.5f).toInt()
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            clipToPadding = false
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scroll.addView(content)
        val preferences = ctx.applicationContext.getSharedPreferences(ElevateFeaturePrefs.FILE, Context.MODE_PRIVATE)
        var savePosterSettings: (() -> Unit)? = null
        ElevateFeature.entries.groupBy { it.section }.forEach { (section, features) ->
            content.addView(TextView(ctx).apply {
                text = section
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(13), 0, dp(6))
            })
            features.forEach { feature ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(9), 0, dp(9))
                }
                val labels = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                }
                labels.addView(TextView(ctx).apply {
                    text = feature.label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                })
                labels.addView(TextView(ctx).apply {
                    text = feature.description
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    alpha = 0.73f
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val posterControls = if (feature == ElevateFeature.LIBRARY_POSTERS) {
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), 0, 0, dp(12))
                    }
                } else null
                val skipControls = if (feature == ElevateFeature.SKIP) {
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), 0, 0, dp(8))
                        val names = listOf(
                            "AUTO_SKIP_OPENING" to "Auto-skip opening",
                            "AUTO_SKIP_ENDING" to "Auto-skip ending",
                            "AUTO_SKIP_RECAP" to "Auto-skip recap",
                            "AUTO_SKIP_MOVIE_CREDITS" to "Auto-skip movie credits"
                        )
                        for ((key, label) in names) {
                            val item = SwitchCompat(ctx).apply {
                                text = label
                                textSize = 13f
                                isChecked = preferences.getBoolean(key, false)
                                setOnCheckedChangeListener { _, checked ->
                                    preferences.edit().putBoolean(key, checked).apply()
                                }
                            }
                            addView(item, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = dp(5) })
                        }
                    }
                } else null
                row.addView(SwitchCompat(ctx).apply {
                    isChecked = preferences.getBoolean(feature.name, ElevateFeaturePrefs.defaultEnabled(feature))
                    contentDescription = feature.label
                    posterControls?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
                    skipControls?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
                    setOnCheckedChangeListener { _, checked ->
                        preferences.edit().putBoolean(feature.name, checked).apply()
                        posterControls?.visibility =
                            if (checked) android.view.View.VISIBLE else android.view.View.GONE
                        skipControls?.visibility =
                            if (checked) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
                content.addView(row)
                skipControls?.let { content.addView(it) }
                posterControls?.let { container ->
                    savePosterSettings = LibraryPosterPrefs.controls(ctx, container, ::dp)
                    content.addView(container)
                }
            }
        }
        return AlertDialog.Builder(ctx)
            .setTitle("Elevate Settings")
            .setView(scroll)
            .setPositiveButton("Save & Close") { _, _ -> savePosterSettings?.invoke() }
            .create()
    }
}
