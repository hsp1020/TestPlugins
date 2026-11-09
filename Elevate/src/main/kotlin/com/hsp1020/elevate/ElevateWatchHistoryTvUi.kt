package com.hsp1020.elevate

import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.SyncWatchType

/** TV detail watch controls: native TV buttons, with no phone-style spinner. */
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
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    private var actionHolder: LinearLayout? = null
    private var action: MaterialButton? = null
    private var dialog: AlertDialog? = null
    private var providerRow: LinearLayout? = null
    private var statusRow: LinearLayout? = null
    private var providerButtons = emptyMap<String, MaterialButton>()
    private var statusButtons = emptyMap<Int, MaterialButton>()
    private var selectionKeys = emptyList<String>()
    private var titleText: TextView? = null
    private var count: TextView? = null
    private var rating: TextView? = null
    private var warning: TextView? = null
    private var saveButton: MaterialButton? = null
    private var controlButtons = emptyList<MaterialButton>()
    private var cached: State? = null

    /** Same result_selection.xml / SelectableButtonTV used by native TV seasons. */
    private fun selection(parent: LinearLayout, label: String, click: () -> Unit): MaterialButton =
        (LayoutInflater.from(parent.context)
            .inflate(R.layout.result_selection, parent, false) as MaterialButton).apply {
            text = label
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { click() }
            parent.addView(this)
        }

    private fun group(name: String): LinearLayout = LinearLayout(root.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
        addView(TextView(context).apply {
            text = name
            textSize = 16f
            setPadding(dp(4), 0, 0, dp(6))
        })
    }

    fun available(enabled: Boolean) {
        if (!fragment.isAdded || fragment.view == null) return
        if (enabled && action == null) {
            val parent = root.findViewById<LinearLayout>(R.id.result_play_parent) ?: return
            val activity = fragment.requireActivity()
            val holder = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            }
            // Reuse the exact native ResultSmallButtonTV style and label typography.
            val button = MaterialButton(
                ContextThemeWrapper(activity, R.style.ResultSmallButtonTV)
            ).apply {
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(40)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                    bottomMargin = dp(4)
                }
                text = "History"
                textSize = 11f
                setAllCaps(false)
                isFocusable = true
                isFocusableInTouchMode = true
                contentDescription = "Watch History Sync"
                setOnClickListener { show() }
            }
            holder.addView(button)
            holder.addView(TextView(
                ContextThemeWrapper(activity, R.style.ResultMarqueeButtonText)
            ).apply {
                text = "Watch History"
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setSingleLine(true)
            })
            parent.addView(holder)
            actionHolder = holder
            action = button
        }
        actionHolder?.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) dismiss()
    }

    private fun show() {
        if (!fragment.isAdded || dialog?.isShowing == true) return
        val activity = fragment.requireActivity()
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            titleText = TextView(context).apply {
                text = "Watch History Sync"
                textSize = 20f
                setPadding(0, 0, 0, dp(12))
            }.also { addView(it) }
        }
        val columns = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        providerRow = group("Provider")
        statusRow = group("Watch status")
        columns.addView(providerRow, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(12)
        })
        columns.addView(statusRow, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(12)
        })
        val details = group(activity.getString(R.string.episodes))
        val episodes = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val epMinus = selection(episodes, "−") { onEpisodes(-1) }
        count = TextView(activity).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(74)
        }
        episodes.addView(count)
        val epPlus = selection(episodes, "+") { onEpisodes(1) }
        details.addView(episodes)
        details.addView(TextView(activity).apply {
            text = activity.getString(R.string.sync_score)
            textSize = 16f
            setPadding(dp(4), dp(8), 0, dp(4))
        })
        val scores = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val scMinus = selection(scores, "−") { onScore(-1) }
        rating = TextView(activity).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(74)
        }
        scores.addView(rating)
        val scPlus = selection(scores, "+") { onScore(1) }
        details.addView(scores)
        controlButtons = listOf(epMinus, epPlus, scMinus, scPlus)
        columns.addView(details)
        outer.addView(columns)
        warning = TextView(activity).apply { setPadding(dp(4), dp(8), 0, dp(8)) }
        outer.addView(warning)
        saveButton = selection(outer, activity.getString(R.string.upload_sync)) { onSave() }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(outer)
        }
        val created = AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog = created
        created.setOnDismissListener {
            dialog = null
            providerRow = null
            statusRow = null
            providerButtons = emptyMap()
            statusButtons = emptyMap()
            selectionKeys = emptyList()
            titleText = null
            count = null
            rating = null
            warning = null
            saveButton = null
            controlButtons = emptyList()
            if (fragment.isAdded && action?.visibility == View.VISIBLE) action?.requestFocus()
        }
        created.show()
        cached?.let(::render)
        providerButtons[cached?.selected]?.requestFocus()
    }

    fun render(state: State) {
        cached = state
        if (dialog?.isShowing != true) return
        titleText?.text = "Watch History Sync" +
            (state.season?.let { " · " + root.context.getString(R.string.season) + " " + it } ?: "")
        if (selectionKeys != state.providers.map { it.first }) {
            selectionKeys = state.providers.map { it.first }
            providerRow?.let { row ->
                row.removeViews(1, row.childCount - 1)
                providerButtons = state.providers.associate { (id, label) ->
                    id to selection(row, label) { onProvider(id) }
                }
            }
        }
        if (statusButtons.isEmpty()) {
            statusRow?.let { row ->
                val labels = listOf(R.string.none, R.string.type_watching,
                    R.string.type_completed, R.string.type_on_hold, R.string.type_dropped,
                    R.string.type_plan_to_watch, R.string.type_re_watching)
                statusButtons = labels.mapIndexed { i, label ->
                    i to selection(row, row.context.getString(label)) {
                        onStatus(SyncWatchType.fromInternalId(i - 1))
                    }
                }.toMap()
            }
        }
        providerButtons.forEach { (id, button) -> button.isSelected = id == state.selected }
        statusButtons.forEach { (i, button) ->
            button.isSelected = state.status?.internalId == i - 1
            button.isEnabled = state.enabled && state.statusEnabled
        }
        count?.text = (state.watched ?: 0).toString() + " / " +
            (state.maximum?.toString() ?: "?")
        rating?.text = (state.score?.toString() ?: "?") + " / 10"
        controlButtons.forEachIndexed { i, button ->
            button.isEnabled = state.enabled && (i < 2 || state.scoreEnabled)
        }
        saveButton?.isEnabled = state.enabled
        warning?.apply {
            text = state.error.orEmpty()
            visibility = if (state.error.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    fun dismiss() { dialog?.dismiss() }

    fun release() {
        dismiss()
        (actionHolder?.parent as? ViewGroup)?.removeView(actionHolder)
        actionHolder = null
        action = null
        cached = null
    }
}
