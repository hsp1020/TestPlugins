package com.hsp1020.elevate

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.content.ComponentName
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import android.graphics.drawable.ColorDrawable
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
    ARTWORK_TRAILERS("Home & Library", "Detail Backdrop & Trailer Picker",
        "Keep detail backgrounds and logos visible, and play trailers from a separate picker instead of replacing the artwork."),
    HERO_CAROUSEL("Home & Library", "Hide Home Hero Carousel",
        "Hide the featured Home banner while keeping search and profile controls available."),
    PHONE_HERO_LAYOUT("Home & Library", "Phone Home Hero Layout",
        "Rearrange the phone Home hero artwork, title, ratings and plot. Unavailable when Hide Home Hero Carousel is enabled."),
    LIVE_HOME_AUTOPLAY("Home & Library", "Live Home Autoplay",
        "Play live streams directly when selecting a Home catalog card; keep native player back navigation."),
    HOME_REPAIR("Home & Library", "Home Poster Refresh",
        "Repair stale Home cards when switching providers."),
    ORIGINAL_THUMBNAIL_RATIOS("Home & Library", "Original Thumbnail Ratios",
        "Preserve landscape thumbnails in Search and Continue Watching; leave YouTube and portrait posters unchanged."),
    HISTORY_SYNC("Home & Library", "History Sync",
        "Report playback progress and watched history to the configured AIOMetadata addon."),
    MDB_RATINGS("Home & Library", "MDB Ratings",
        "Show MDBList ratings before the plot on every provider's detail page."),
    EXTERNAL_DOWNLOADS("Home & Library", "External Downloads",
        "Send native download requests directly to the selected app and recognize files in CloudStream's download folder."),
    LIBRARY_POSTERS("Home & Library", "Custom Posters",
        "Replace library and detail recommendation covers via RPDB, XRDB, OPDB, AIORatings, TOP Posters or EasyRatingsDB."),
    EPISODE_PROGRESS("Home & Library", "Episode Progress Indicators",
        "Show watched progress on large episode thumbnails."),
    EPISODE_SPOILERS("Home & Library", "Blur Unwatched Episodes",
        "Blur episode thumbnails until 90% watched or manually marked watched."),
    EPISODE_OVERLAY("Playback & Subtitles", "Player Episode List",
        "Keep playback running when browsing episodes, with a season dropdown and translucent cards."),
    PLAYER_PANEL("Playback & Subtitles", "Unified Player Panel",
        "Open Sources, Audio, Subtitles and Episodes in one right-side panel without pausing playback."),
    COMPACT_PLAYER_CONTROLS("Playback & Subtitles", "Compact Player Controls",
        "Use 54x48dp icon-only buttons with 12dp gaps on phones and tablets when player button names are hidden."),
    SUBTITLE_REFRESH("Playback & Subtitles", "Subtitle Refresh",
        "Enable manual and long-press automatic refresh for external text subtitles across providers."),
    EMBEDDED_SUBTITLES("Playback & Subtitles", "Embedded Subtitle Management",
        "Prefer embedded subtitles matching CloudStream's subtitle auto-select language and remove stale tracks when videos change."),
    SUBTITLE_LANGUAGE_NAMES("Playback & Subtitles", "Subtitle Language Names",
        "Localize online subtitle languages and group matching tracks using CloudStream's native language names."),
    AUDIO_TRACKS("Playback & Subtitles", "Automatic Audio Language",
        "Select app-language audio, then Japanese for anime or English for other titles."),
    SKIP("Playback & Subtitles", "Unified Skip Intervals",
        "Provide skip segments using native timestamps, AniSkip, Anime Skip, IntroDb and TheIntroDB. Configure automatic skipping separately below."),
    PIP_LIBRARY_BROWSE("Playback & Subtitles", "Browse Library in PiP",
        "Keep native Android PiP playback while browsing CloudStream Home, Search and Library."),
    PIP_BUTTON("Playback & Subtitles", "Picture-in-Picture Button",
        "Show a native-style PiP player button when PiP is enabled in CloudStream."),
    PIP_SUBTITLES("Playback & Subtitles", "PiP Subtitle Styling",
        "Adjust subtitle scale and placement while in Picture-in-Picture."),
    SUBTITLE_RETRY("Playback & Subtitles", "Subtitle Referer Retry",
        "Retry blocked subtitle downloads using the current video's source information when needed."),
    HTTP_RESPONSE_CLEANUP("Stability & Repairs", "HTTP Response Cleanup",
        "Release unused network responses from StreamPlay/CineStream and header-only requests to help prevent connection leaks."),
    NATIVE_LISTENER_CLEANUP("Stability & Repairs", "List Scroll Listener Cleanup",
        "Remove duplicate, outdated scroll listeners from Home and the player episode list while keeping current listeners active."),
    SUBTITLE_SYNC_REPAIR("Stability & Repairs", "Subtitle Sync List Recovery",
        "Restore the subtitle sync list from the active subtitle renderer if CloudStream loses its decoder reference.")
}

internal object ElevateFeaturePrefs {
    const val FILE = "ElevateFeatures"
    fun defaultEnabled(@Suppress("UNUSED_PARAMETER") feature: ElevateFeature): Boolean = false
    fun enabled(context: Context, feature: ElevateFeature): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(feature.name, defaultEnabled(feature))
}

/** The same native-font dropdown and background as the Elevate settings dialog. */
internal fun Spinner.useElevateSettingsDropdown(
    context: Context, labels: List<String>, initialLabel: (() -> String?)? = null
) {
    val surface = context.colorFromAttribute(R.attr.primaryGrayBackground)
    val textColor = context.colorFromAttribute(R.attr.textColor)
    setPopupBackgroundDrawable(ColorDrawable(surface))
    adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).also {
                (it as? TextView)?.apply {
                    applyElevateNativeFont()
                    setBackgroundColor(surface)
                    setTextColor(textColor)
                    initialLabel?.invoke()?.let { text = it }
                }
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getDropDownView(position, convertView, parent).also {
                (it as? TextView)?.apply {
                    applyElevateNativeFont()
                    setBackgroundColor(surface)
                    setTextColor(textColor)
                }
            }
    }
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
        var phoneHeroSwitch: SwitchCompat? = null
        var phoneHeroLabels: LinearLayout? = null
        fun updatePhoneHeroAvailability() {
            val available = !preferences.getBoolean(ElevateFeature.HERO_CAROUSEL.name, false)
            phoneHeroSwitch?.isEnabled = available
            phoneHeroLabels?.alpha = if (available) 1f else 0.5f
        }
        var savePosterSettings: (() -> Unit)? = null
        ElevateFeature.entries.groupBy { it.section }.forEach { (section, features) ->
            content.addView(TextView(ctx).apply {
                text = section
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                applyElevateNativeFont(bold = true)
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
                    applyElevateNativeFont()
                })
                labels.addView(TextView(ctx).apply {
                    text = feature.description
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    applyElevateNativeFont()
                    alpha = 0.73f
                    setPadding(0, dp(3), 0, 0)
                })
                row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (feature == ElevateFeature.PHONE_HERO_LAYOUT) phoneHeroLabels = labels
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
                        // Automatic skipping only controls seeking; manual native buttons stay enabled.
                        addView(TextView(ctx).apply {
                            text = "Automatic Skipping"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            applyElevateNativeFont(bold = true)
                            setPadding(0, dp(10), 0, dp(5))
                        })
                        // Keep existing preference keys, only match Nuvio's labels and descriptions.
                        val skipItems = listOf(
                            Triple(
                                "AUTO_SKIP_OPENING",
                                "Intro / Opening",
                                "Skip intros and anime openings automatically."
                            ),
                            Triple(
                                "AUTO_SKIP_RECAP",
                                "Recap",
                                "Skip recap segments automatically."
                            ),
                            Triple(
                                "AUTO_SKIP_ENDING",
                                "Outro / Ending",
                                "Skip outros and anime endings automatically."
                            ),
                            Triple(
                                "AUTO_SKIP_MOVIE_CREDITS",
                                "End Credits",
                                "Skip movie credits, keeping post-credits scenes."
                            )
                        )
                        for ((key, title, description) in skipItems) {
                            val itemRow = LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(0, dp(6), 0, dp(6))
                            }
                            val itemLabels = LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                            }
                            itemLabels.addView(TextView(ctx).apply {
                                text = title
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                                applyElevateNativeFont()
                            })
                            itemLabels.addView(TextView(ctx).apply {
                                text = description
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                applyElevateNativeFont()
                                alpha = 0.73f
                                setPadding(0, dp(3), 0, 0)
                            })
                            itemRow.addView(
                                itemLabels,
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            )
                            itemRow.addView(SwitchCompat(ctx).apply {
                                contentDescription = title
                                isChecked = preferences.getBoolean(key, false)
                                setOnCheckedChangeListener { _, checked ->
                                    preferences.edit().putBoolean(key, checked).apply()
                                }
                            }, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginStart = dp(12) })
                            addView(itemRow, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ))
                        }
                    }
                } else null
                val historyControls = if (feature == ElevateFeature.HISTORY_SYNC) {
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), 0, 0, dp(12))
                        addView(EditText(ctx).apply {
                            hint = "AIOMetadata Addon URL"
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_VARIATION_URI
                            isSingleLine = true
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            applyElevateNativeFont()
                            setText(preferences.getString("HISTORY_SYNC_URL", "").orEmpty())
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    preferences.edit().putString("HISTORY_SYNC_URL", s.toString().trim()).apply()
                                }
                                override fun afterTextChanged(s: Editable?) = Unit
                            })
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ))
                    }
                } else null
                val mdbControls = if (feature == ElevateFeature.MDB_RATINGS) {
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), 0, 0, dp(12))
                        addView(EditText(ctx).apply {
                            hint = "MDBList API Key"
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                            isSingleLine = true
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            applyElevateNativeFont()
                            setText(preferences.getString("MDB_RATINGS_API_KEY", "").orEmpty())
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    preferences.edit().putString("MDB_RATINGS_API_KEY", s.toString().trim()).apply()
                                }
                                override fun afterTextChanged(s: Editable?) = Unit
                            })
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ))
                    }
                } else null
                val externalControls = if (feature == ElevateFeature.EXTERNAL_DOWNLOADS) {
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), 0, 0, dp(12))
                        addView(TextView(ctx).apply {
                            text = "Download App"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            applyElevateNativeFont()
                            setPadding(0, dp(8), 0, dp(4))
                        })
                        val apps = listOf(
                            Triple("1DM", "idm.internet.download.manager", "idm.internet.download.manager.Downloader"),
                            Triple("1DM+", "idm.internet.download.manager.plus", "idm.internet.download.manager.Downloader"),
                            Triple("1DM Lite", "idm.internet.download.manager.adm.lite", "idm.internet.download.manager.Downloader"),
                            Triple("ADM", "com.dv.adm", "com.dv.adm.AEditor")
                        )
                        val selected = ExternalDownloads.selected(ctx)
                        val saved = ComponentName.unflattenFromString(
                            selected?.component.orEmpty()
                        )?.packageName?.takeIf { pkg ->
                            runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
                        }
                        if (selected != null && saved == null) ExternalDownloads.clearSelection(ctx)
                        var unselected = saved == null || apps.none { it.second == saved }
                        val selectorRow = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        selectorRow.addView(object : Spinner(ctx) {
                            private var openedByUser = false
                            override fun performClick(): Boolean {
                                openedByUser = true
                                return super.performClick()
                            }
                            override fun setSelection(position: Int) {
                                // Ignore Android's automatic first-row selection on initial layout.
                                if (!openedByUser || position !in apps.indices) {
                                    super.setSelection(position)
                                    return
                                }
                                val (label, pkg, component) = apps[position]
                                openedByUser = false
                                if (runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isFailure) {
                                    val previous = ComponentName.unflattenFromString(
                                        ExternalDownloads.selected(ctx)?.component.orEmpty()
                                    )?.packageName
                                    super.setSelection(apps.indexOfFirst { it.second == previous }.coerceAtLeast(0))
                                    android.widget.Toast.makeText(ctx, "Please install $label.", android.widget.Toast.LENGTH_LONG).show()
                                    return
                                }
                                super.setSelection(position)
                                ExternalDownloads.select(
                                    ctx, ExternalDownloads.App(
                                        ComponentName(pkg, component).flattenToString(), label, "direct"
                                    )
                                )
                                unselected = false
                                (adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                            }
                        }.apply {
                            useElevateSettingsDropdown(ctx, apps.map { it.first }) {
                                if (unselected) "Select app" else null
                            }
                            setSelection(apps.indexOfFirst { it.second == saved }.coerceAtLeast(0))
                        }, LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                        ))
                        selectorRow.addView(androidx.appcompat.widget.AppCompatButton(
                            ctx, null, androidx.appcompat.R.attr.buttonBarPositiveButtonStyle
                        ).apply {
                            text = "Sync Download Files"
                            isAllCaps = false
                            setOnClickListener { ExternalDownloads.syncNow(ctx.applicationContext) }
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = dp(10) })
                        addView(selectorRow)
                    }
                } else null
                row.addView(SwitchCompat(ctx).apply {
                    isChecked = preferences.getBoolean(feature.name, ElevateFeaturePrefs.defaultEnabled(feature))
                    contentDescription = feature.label
                    if (feature == ElevateFeature.PHONE_HERO_LAYOUT) {
                        phoneHeroSwitch = this
                        updatePhoneHeroAvailability()
                    }
                    posterControls?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
                    skipControls?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
                    externalControls?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
                    historyControls?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
                    mdbControls?.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
                    setOnCheckedChangeListener { _, checked ->
                        preferences.edit().putBoolean(feature.name, checked).apply()
                        if (feature == ElevateFeature.HERO_CAROUSEL) updatePhoneHeroAvailability()
                        posterControls?.visibility =
                            if (checked) android.view.View.VISIBLE else android.view.View.GONE
                        skipControls?.visibility =
                            if (checked) android.view.View.VISIBLE else android.view.View.GONE
                        externalControls?.visibility =
                            if (checked) android.view.View.VISIBLE else android.view.View.GONE
                        historyControls?.visibility =
                            if (checked) android.view.View.VISIBLE else android.view.View.GONE
                        mdbControls?.visibility =
                            if (checked) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
                content.addView(row)
                historyControls?.let { content.addView(it) }
                mdbControls?.let { content.addView(it) }
                externalControls?.let { content.addView(it) }
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
            .setPositiveButton("Save") { _, _ -> savePosterSettings?.invoke() }
            .create()
    }
}
