package com.hsp1020.elevate

import android.text.Editable
import android.text.TextWatcher
import android.content.Context
import android.util.Xml
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import org.xmlpull.v1.XmlPullParser

/** TV uses the native right-side episode treatment and the phone's sync controls. */
internal class ElevateWatchHistoryTvUi(
    private val fragment: Fragment,
    private val root: View,
    private val onProvider: (String) -> Unit,
    private val onStatus: (SyncWatchType) -> Unit,
    private val onEpisodes: (Int) -> Unit,
    private val onScore: (Int) -> Unit,
    private val onSave: () -> Unit
) {
    internal data class State(
        val season: Int?,
        val providers: List<Pair<String, String>>,
        val selected: String?,
        val status: SyncWatchType?,
        val watched: Int?,
        val maximum: Int?,
        val score: Int?,
        val enabled: Boolean,
        val statusEnabled: Boolean,
        val scoreEnabled: Boolean,
        val error: String?
    )

    private val density get() = root.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()
    private var actionHolder: LinearLayout? = null
    private var action: MaterialButton? = null
    private var overlay: FrameLayout? = null
    private var backCallback: OnBackPressedCallback? = null
    private var providerRow: LinearLayout? = null
    private var providerButtons = emptyMap<String, MaterialButton>()
    private var selectionKeys = emptyList<String>()
    private var current: EditText? = null
    private var maximum: TextView? = null
    private var progress: ProgressBar? = null
    private var scoreText: TextView? = null
    private var scoreSlider: Slider? = null
    private var statuses: ListView? = null
    private var minus: ImageView? = null
    private var plus: ImageView? = null
    private var saveButton: MaterialButton? = null
    private var warning: TextView? = null
    private var cached: State? = null
    private var rendering = false

    /** Build from CloudStream's own TV search-button XML attributes, not a theme wrapper. */
    private fun nativeAction(activity: Context): Pair<MaterialButton, TextView>? {
        val parser = activity.resources.getXml(R.layout.fragment_result_tv)
        try {
            var button: MaterialButton? = null
            var label: TextView? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val attrs = Xml.asAttributeSet(parser)
                when (attrs.getAttributeResourceValue(
                    "http://schemas.android.com/apk/res/android", "id", 0
                )) {
                    R.id.result_search_Button -> button = MaterialButton(activity, attrs)
                    R.id.result_search_text -> label = TextView(activity, attrs)
                }
                if (button != null && label != null) return button to label
            }
        } finally {
            parser.close()
        }
        return null
    }

    /** Exact native XML styling, with only the icon, translation and callback changed. */
    fun available(enabled: Boolean) {
        if (!fragment.isAdded || fragment.view == null) return
        if (enabled && action == null) {
            val parent = root.findViewById<LinearLayout>(R.id.result_play_parent) ?: return
            val activity = fragment.requireActivity()
            val (button, label) = nativeAction(activity) ?: return
            val holder = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(8)
                }
            }
            button.apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(40)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                    bottomMargin = dp(4)
                }
                setIconResource(R.drawable.bookmark_star_24px)
                iconPadding = 0
                text = null
                isFocusable = true
                contentDescription = activity.getString(R.string.upload_sync)
                nextFocusUpId = id
                nextFocusDownId = R.id.result_description
                setOnClickListener { show() }
            }
            label.apply {
                id = View.NO_ID
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                text = activity.getString(R.string.upload_sync)
            }
            button.setOnFocusChangeListener { _, focused -> label.isSelected = focused }
            holder.addView(button)
            holder.addView(label)
            parent.addView(holder)
            actionHolder = holder
            action = button
        }
        actionHolder?.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) dismiss()
    }

    /** The actual native result_sync layout supplies the episode bar, rating and watch list. */
    private fun show() {
        if (!fragment.isAdded || overlay != null) return
        val host = root as? ViewGroup ?: return
        val activity = fragment.requireActivity()
        val screenWidth = root.width.takeIf { it > 0 }
            ?: root.resources.displayMetrics.widthPixels
        val panelWidth = minOf(
            screenWidth,
            maxOf(dp(320), minOf(dp(600), (screenWidth * 0.38f).toInt()))
        )
        val surface = FrameLayout(activity)
        val shade = ImageView(activity).apply {
            setImageResource(R.drawable.episodes_shadow)
            scaleType = ImageView.ScaleType.FIT_XY
            if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
        }
        surface.addView(shade, FrameLayout.LayoutParams(
            minOf(screenWidth, panelWidth + dp(240)), -1, Gravity.END
        ))
        val panel = FrameLayout(activity).apply {
            setBackgroundColor(activity.colorFromAttribute(R.attr.primaryBlackBackground))
            isClickable = true
        }
        surface.addView(panel, FrameLayout.LayoutParams(panelWidth, -1, Gravity.END))
        surface.setOnClickListener { dismiss() }

        val phone = LayoutInflater.from(activity).inflate(R.layout.result_sync, panel, false)
        panel.addView(phone, FrameLayout.LayoutParams(-1, -1))
        phone.findViewById<View>(R.id.result_sync_loading_shimmer)?.visibility = View.GONE
        val holder = phone.findViewById<ScrollView>(R.id.result_sync_holder)
        holder.visibility = View.VISIBLE
        holder.isFillViewport = false
        val names = phone.findViewById<TextView>(R.id.result_sync_names)
        val column = names.parent as LinearLayout
        providerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(0, 0, 0, dp(10))
        }
        column.addView(providerRow, column.indexOfChild(names))
        column.removeView(names)

        current = phone.findViewById(R.id.result_sync_current_episodes)
        maximum = phone.findViewById(R.id.result_sync_max_episodes)
        progress = phone.findViewById(R.id.result_sync_episodes)
        scoreText = phone.findViewById(R.id.result_sync_score_text)
        scoreSlider = phone.findViewById(R.id.result_sync_rating)
        statuses = phone.findViewById(R.id.result_sync_check)
        minus = phone.findViewById(R.id.result_sync_sub_episode)
        plus = phone.findViewById(R.id.result_sync_add_episode)
        saveButton = phone.findViewById(R.id.result_sync_set_score)
        warning = TextView(activity).apply {
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }
        column.addView(warning, column.indexOfChild(saveButton))

        val labels = listOf(
            R.string.none, R.string.type_watching, R.string.type_completed,
            R.string.type_on_hold, R.string.type_dropped,
            R.string.type_plan_to_watch, R.string.type_re_watching
        ).map(activity::getString)
        statuses?.apply {
            choiceMode = AbsListView.CHOICE_MODE_SINGLE
            adapter = ArrayAdapter<String>(activity, R.layout.sort_bottom_single_choice, labels)
            isNestedScrollingEnabled = false
            val sampleWidth = View.MeasureSpec.makeMeasureSpec(
                panelWidth - dp(32), View.MeasureSpec.EXACTLY
            )
            val itemAdapter = adapter
            var totalHeight = 0
            for (i in 0 until itemAdapter.count) {
                val item = itemAdapter.getView(i, null, this)
                item.measure(sampleWidth, View.MeasureSpec.makeMeasureSpec(
                    0, View.MeasureSpec.UNSPECIFIED
                ))
                totalHeight += item.measuredHeight
            }
            layoutParams = layoutParams.apply {
                height = totalHeight + dividerHeight * (itemAdapter.count - 1)
            }
            setOnItemClickListener { _, _, position, _ ->
                onStatus(SyncWatchType.fromInternalId(position - 1))
            }
        }
        listOf(minus, plus).forEach { control ->
            control?.isFocusable = true
            control?.isClickable = true
        }
        minus?.setOnClickListener { onEpisodes(-1) }
        plus?.setOnClickListener { onEpisodes(1) }
        current?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (rendering || current?.hasFocus() != true) return
                val value = s?.toString()?.toIntOrNull() ?: return
                onEpisodes(value - (cached?.watched ?: 0))
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        scoreSlider?.apply {
            isFocusable = true
            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !rendering) onScore(value.toInt() - (cached?.score ?: 0))
            }
        }
        saveButton?.setOnClickListener { onSave() }

        overlay = surface
        host.addView(surface, ViewGroup.LayoutParams(-1, -1))
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { dismiss() }
        }.also { activity.onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, it) }
        surface.alpha = 0f
        surface.translationX = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            -dp(100).toFloat() else dp(100).toFloat()
        surface.animate().alpha(0.97f).translationX(0f)
            .setInterpolator(DecelerateInterpolator()).setDuration(200).start()
        cached?.let(::render)
        providerButtons[cached?.selected]?.requestFocus()
            ?: providerButtons.values.firstOrNull()?.requestFocus()
    }

    /** Render the same draft tracked by the phone implementation; no TV-specific sync API. */
    fun render(state: State) {
        cached = state
        if (overlay == null) return
        rendering = true
        try {
            if (selectionKeys != state.providers.map { it.first }) {
                selectionKeys = state.providers.map { it.first }
                providerRow?.let { row ->
                    row.removeAllViews()
                    providerButtons = state.providers.associate { (id, label) ->
                        val button = LayoutInflater.from(row.context)
                            .inflate(R.layout.result_selection, row, false) as MaterialButton
                        button.text = label
                        button.minWidth = 0
                        button.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                            marginStart = dp(2)
                            marginEnd = dp(2)
                        }
                        button.iconPadding = 0
                        button.setPadding(dp(2), 0, dp(2), 0)
                        button.isFocusable = true
                        button.setOnClickListener { onProvider(id) }
                        row.addView(button)
                        id to button
                    }
                }
            }
            providerButtons.forEach { (id, button) ->
                button.isSelected = id == state.selected
            }
            val watched = state.watched ?: 0
            val total = state.maximum?.takeIf { it > 0 }
            val maxText = if (total == null) "/ ?" else "/ " + total
            if (maximum?.text?.toString() != maxText) maximum?.text = maxText
            val watchedText = state.watched?.toString().orEmpty()
            if (current?.text?.toString() != watchedText) {
                current?.setText(watchedText)
                current?.setSelection(watchedText.length)
            }
            val barMax = (total ?: maxOf(1, watched)).coerceAtMost(1_000_000) * 1000
            val barValue = watched.coerceIn(0, 1_000_000) * 1000
            progress?.max = barMax
            progress?.progress = barValue.coerceAtMost(barMax)
            val checked = state.status?.internalId?.plus(1) ?: -1
            if (checked in 0..6) statuses?.setItemChecked(checked, true)
            else statuses?.clearChoices()
            val score = state.score?.coerceIn(0, 10)
            val scoreLabel = score?.let { it.toString() + "/10" } ?: "?"
            if (scoreText?.text?.toString() != scoreLabel) scoreText?.text = scoreLabel
            scoreSlider?.value = (score ?: 0).toFloat()
            current?.isEnabled = state.enabled
            minus?.isEnabled = state.enabled
            plus?.isEnabled = state.enabled
            statuses?.isEnabled = state.enabled && state.statusEnabled
            scoreSlider?.isEnabled = state.enabled && state.scoreEnabled
            saveButton?.isEnabled = state.enabled
            warning?.apply {
                text = state.error.orEmpty()
                visibility = if (state.error.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        } finally {
            rendering = false
        }
    }

    fun dismiss() {
        val surface = overlay ?: return
        overlay = null
        backCallback?.remove()
        backCallback = null
        providerRow = null
        providerButtons = emptyMap()
        selectionKeys = emptyList()
        current = null
        maximum = null
        progress = null
        scoreText = null
        scoreSlider = null
        statuses = null
        minus = null
        plus = null
        saveButton = null
        warning = null
        action?.takeIf { it.visibility == View.VISIBLE }?.requestFocus()
        surface.animate().cancel()
        val exit = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) -dp(100) else dp(100)
        surface.animate().alpha(0f).translationX(exit.toFloat())
            .setInterpolator(DecelerateInterpolator()).setDuration(200)
            .withEndAction { (surface.parent as? ViewGroup)?.removeView(surface) }
            .start()
    }

    fun release() {
        dismiss()
        (actionHolder?.parent as? ViewGroup)?.removeView(actionHolder)
        actionHolder = null
        action = null
        cached = null
    }
}
