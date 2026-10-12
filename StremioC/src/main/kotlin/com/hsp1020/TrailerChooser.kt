package com.hsp1020

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/** StremioC-only trailer picker: no fake episodes or modifications to player internals. */
internal class TrailerChooser(
    private val fragment: Fragment,
    private val root: View,
    private val detailUrl: String?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var button: TextView? = null
    private var dialog: BottomSheetDialog? = null
    private var opening = false
    private var items: List<TrailerChoice> = emptyList()
    private val tvButton = find("result_play_trailer_button")
    private val tvContainer = find("result_play_trailer")
    private val isTv = tvButton != null && tvContainer != null
    private val pickerClick = View.OnClickListener { showPicker() }
    private val tvPreDraw = ViewTreeObserver.OnPreDrawListener {
        if (fragment.isAdded) {
            // Host trailer LiveData may reset the native TV button after page metadata arrives.
            refresh()
            syncTvButton()
        }
        true
    }

    private fun find(name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id == 0) null else root.findViewById(id)
    }
    private val title: TextView? = root.resources.getIdentifier("result_title", "id", root.context.packageName)
        .takeIf { it != 0 }?.let { root.findViewById(it) }
    private val titleWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = refresh()
    }

    private fun dp(value: Int): Int = (value * root.resources.displayMetrics.density + 0.5f).toInt()

    private fun localizedLanguage(raw: String?): String? {
        val code = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val appLocale = root.resources.configuration.locales.get(0).toLanguageTag()
        return SubtitleHelper.fromTagToLanguageName(code, appLocale) ?: code.uppercase(java.util.Locale.ROOT)
    }

    private fun themeColor(attrName: String, fallback: Int): Int {
        val id = root.resources.getIdentifier(attrName, "attr", root.context.packageName)
        if (id == 0) return fallback
        val out = TypedValue()
        return if (root.context.theme.resolveAttribute(id, out, true)) out.data else fallback
    }

    fun start() {
        title?.addTextChangedListener(titleWatcher)
        if (isTv) {
            tvButton?.apply {
                setOnClickListener(pickerClick)
                setOnKeyListener { _, code, event ->
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    ) {
                        if (event.action == KeyEvent.ACTION_UP) showPicker()
                        true
                    } else false
                }
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> true
                        MotionEvent.ACTION_UP -> { showPicker(); true }
                        else -> false
                    }
                }
            }
            root.viewTreeObserver.addOnPreDrawListener(tvPreDraw)
        }
        refresh()
    }

    private fun syncTvButton() {
        if (!isTv) return
        val desired = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (tvContainer?.visibility != desired) tvContainer?.visibility = desired
        // The host can replace the click listener even while this button stays visible.
        if (desired == View.VISIBLE) tvButton?.setOnClickListener(pickerClick)
    }

    fun refresh() {
        if (!fragment.isAdded || detailUrl.isNullOrBlank()) return
        val context = root.context
        val settingId = context.resources.getIdentifier("show_trailers_key", "string", context.packageName)
        val enabled = settingId == 0 || PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(settingId), true)
        val next = if (enabled) StremioC.trailerChoicesByUrl[detailUrl].orEmpty() else emptyList()
        if (next == items && (isTv || button != null || next.isEmpty())) {
            if (!isTv && button != null) updatePhoneLabel()
            return
        }
        items = next
        if (isTv) {
            syncTvButton()
            if (items.isEmpty()) dialog?.dismiss()
            return
        }
        if (items.isEmpty()) {
            (button?.parent as? ViewGroup)?.removeView(button)
            button = null
            dialog?.dismiss()
            return
        }
        val id = context.resources.getIdentifier("result_top_holder", "id", context.packageName)
        val hero = if (id != 0) root.findViewById<FrameLayout>(id) else null
        if (hero == null) return
        if (button == null) {
            button = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = GradientDrawable().apply {
                    setColor(0x4D2B2C30.toInt())
                    cornerRadius = context.resources.getDimension(R.dimen.rounded_button_radius)
                }
                isClickable = true
                isFocusable = true
                elevation = dp(4).toFloat()
                setOnClickListener { showPicker() }
            }.also {
                hero.addView(it, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP or Gravity.END
                ).apply { topMargin = dp(16); marginEnd = dp(16) })
            }
        }
        updatePhoneLabel()
    }

    private fun updatePhoneLabel() {
        val label = root.context.getString(R.string.trailer)
        button?.text = "▶  $label (${items.size})"
        button?.contentDescription = "${root.context.getString(R.string.play_trailer_button)} (${items.size})"
    }

    private fun showPicker() {
        val activity = fragment.activity ?: return
        if (dialog?.isShowing == true || opening || items.isEmpty()) return
        val foreground = themeColor("textColor", Color.WHITE)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        list.addView(TextView(activity).apply {
            text = "${activity.getString(R.string.trailer)} (${items.size})"
            textSize = 20f
            setTextColor(foreground)
            setPadding(dp(4), dp(8), 0, dp(16))
        })
        val scroll = ScrollView(activity)
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        items.forEach { entry ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(78)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                isClickable = true
                isFocusable = true
                val focusBackground = TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, focusBackground, true) &&
                    focusBackground.resourceId != 0) setBackgroundResource(focusBackground.resourceId)
                setOnClickListener {
                    dialog?.dismiss()
                    play(entry)
                }
            }
            row.addView(ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                loadImage("https://i.ytimg.com/vi/" + entry.youtubeId + "/hqdefault.jpg")
            }, LinearLayout.LayoutParams(dp(112), dp(63)))
            val labels = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            labels.addView(TextView(activity).apply {
                text = entry.title
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(foreground)
            })
            labels.addView(TextView(activity).apply {
                text = (if (entry.type.equals("Trailer", true)) activity.getString(R.string.trailer) else entry.type) +
                    (localizedLanguage(entry.language)?.let { " · " + it } ?: "")
                textSize = 12f
                setTextColor(themeColor("textColor", 0xFFAAAAAA.toInt()))
                alpha = 0.7f
            })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = "▶"
                textSize = 18f
                setTextColor(foreground)
            })
            rows.addView(row)
        }
        scroll.addView(rows)
        list.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf(dp(78) * items.size + dp(1), (activity.resources.displayMetrics.heightPixels * 0.6f).toInt())
        ))
        dialog = BottomSheetDialog(activity).apply {
            setContentView(list)
            setOnDismissListener { dialog = null }
            if (isTv) setOnShowListener { rows.getChildAt(0)?.requestFocus() }
            show()
        }
    }

    private fun play(entry: TrailerChoice) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                val subtitleFiles = Collections.synchronizedList(mutableListOf<SubtitleFile>())
                withContext(Dispatchers.IO) {
                    com.hsp1020.youtube.YoutubeExtractor().getUrl(
                        "https://www.youtube.com/watch?v=" + entry.youtubeId,
                        null,
                        { subtitle -> subtitleFiles.add(subtitle) },
                        { link -> links.add(link) }
                    )
                }
                if (!fragment.isAdded) return@launch
                val playable = synchronized(links) { links.toList() }.filter {
                    it.source == "YouTube" && it.type in setOf(
                        ExtractorLinkType.VIDEO, ExtractorLinkType.DASH, ExtractorLinkType.M3U8
                    )
                }
                if (playable.isEmpty()) {
                    toast(fragment.requireContext().getString(R.string.no_links_found_toast))
                    return@launch
                }
                val subtitles = synchronized(subtitleFiles) { subtitleFiles.toList() }
                    .distinctBy { it.url }.map { PlayerSubtitleHelper.getSubtitleData(it) }
                // Pass every MP4/HLS/DASH quality and track-bearing DASH MPD to the host player.
                val selectedIndex = items.indexOf(entry).takeIf { it >= 0 } ?: return@launch
                val heading = title?.text?.toString()?.trim().orEmpty()
                    .ifBlank { root.context.getString(R.string.trailer) }
                fragment.activity?.navigate(
                    R.id.global_to_navigation_player,
                    GeneratorPlayer.newInstance(
                        TrailerPlaybackGenerator(
                            items.toList(), heading, selectedIndex,
                            playable.distinctBy { it.url }, subtitles
                        ), selectedIndex
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("StremioCTrailers", "Trailer playback failed", error)
                if (fragment.isAdded) toast(fragment.requireContext().getString(R.string.error))
            } finally {
                opening = false
            }
        }
    }

    private fun toast(message: String) {
        fragment.activity?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    fun stop() {
        title?.removeTextChangedListener(titleWatcher)
        if (isTv) {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(tvPreDraw)
            tvButton?.setOnKeyListener(null)
            tvButton?.setOnTouchListener(null)
        }
        dialog?.dismiss()
        dialog = null
        (button?.parent as? ViewGroup)?.removeView(button)
        button = null
        items = emptyList()
        scope.cancel()
    }
}
