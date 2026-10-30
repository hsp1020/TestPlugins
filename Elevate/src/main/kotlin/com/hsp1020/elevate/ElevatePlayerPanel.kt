package com.hsp1020.elevate

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Button
import android.text.InputType
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.util.TypedValue
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import java.text.Normalizer

/** Four-tab extension of the existing native right-side episode overlay. */
internal class ElevatePlayerPanel(
    private val fragment: GeneratorPlayer,
    private val root: View,
    private val overlay: LinearLayout,
    private val episodeTitle: TextView?,
    private val episodeList: RecyclerView?,
    private val seasonSelector: () -> View?,
    private val openNative: () -> Boolean
) {
    enum class Tab { SOURCES, AUDIO, SUBTITLES, EPISODES }
    private data class Row(val label: String, val action: () -> Unit,
        val refresh: (() -> Unit)? = null, val autoRefresh: (() -> Unit)? = null,
        val subtitleLanguage: String? = null, val subtitleDetails: String? = null)
    private val ctx = overlay.context
    private val dp: (Int) -> Int = { (it * ctx.resources.displayMetrics.density + .5f).toInt() }
    private val textColor = episodeTitle?.currentTextColor ?: Color.WHITE
    private val vm = ViewModelProvider(fragment)[PlayerGeneratorViewModel::class.java]
    private val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val buttons = LinkedHashMap<Tab, TextView>()
    private val filters = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val groupScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(filters)
    }
    private val rows = ArrayList<Row>()
    private val list = ListView(ctx).apply {
        divider = null
        selector = ColorDrawable(Color.TRANSPARENT)
    }
    private val adapter = object : ArrayAdapter<Row>(ctx, android.R.layout.simple_list_item_1, rows) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val value = getItem(position) ?: return TextView(ctx)
            val holder = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), 0, 0, 0)
                val attr = TypedValue()
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true) &&
                    attr.resourceId != 0) background = AppCompatResources.getDrawable(ctx, attr.resourceId)
                addView(TextView(ctx).apply {
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                    applyElevateNativeFont()
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(ctx).apply {
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                    applyElevateNativeFont()
                }, LinearLayout.LayoutParams(0, -2, .65f))
                addView(ImageButton(ctx).apply {
                    setImageResource(android.R.drawable.ic_popup_sync)
                    contentDescription = "Refresh subtitle"
                    background = null
                    isFocusable = true
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            (holder.getChildAt(0) as TextView).apply {
                text = value.subtitleLanguage ?: value.label
                layoutParams = LinearLayout.LayoutParams(0, -2,
                    if (value.subtitleLanguage != null) .35f else 1f)
            }
            (holder.getChildAt(1) as TextView).apply {
                text = value.subtitleDetails.orEmpty()
                visibility = if (value.subtitleLanguage != null) View.VISIBLE else View.GONE
            }
            (holder.getChildAt(2) as ImageButton).apply {
                visibility = if (value.refresh != null) View.VISIBLE else View.GONE
                setOnClickListener(if (value.refresh != null) View.OnClickListener {
                    value.refresh.invoke()
                } else null)
                setOnLongClickListener(if (value.autoRefresh != null) View.OnLongClickListener {
                    value.autoRefresh.invoke()
                    true
                } else null)
            }
            return holder
        }
    }
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(groupScroll, LinearLayout.LayoutParams(-1, dp(42)))
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private var tab = Tab.EPISODES
    private var filter: String? = null
    private val offsetInput = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        setSingleLine(true)
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        hint = "ms"
    }
    private val offsetEditor = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(offsetInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(Button(ctx).apply {
            text = "Apply"
            setOnClickListener {
                offsetInput.text.toString().toLongOrNull()?.let { amount ->
                    fragment.player.setSubtitleOffset(amount)
                    ElevateSubtitleRefreshHook.active()?.refreshAppearance()
                    showSync()
                }
            }
        }, LinearLayout.LayoutParams(dp(96), dp(48)))
    }
    private val touches = ArrayList<View>()
    private val linksChanged = Observer<VideoLive<Set<VideoLink>>> {
        if (tab == Tab.SOURCES) showTabContents()
    }
    private val subtitlesChanged = Observer<VideoLive<Set<SubtitleData>>> {
        if (tab == Tab.SUBTITLES) showTabContents()
    }

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.action?.invoke() }
        for (entry in Tab.entries) {
            val button = TextView(ctx).apply {
                text = when (entry) {
                    Tab.SOURCES -> "Sources"
                    Tab.AUDIO -> "Audio"
                    Tab.SUBTITLES -> "Subtitles"
                    Tab.EPISODES -> "Episodes"
                }
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(textColor)
                minimumHeight = dp(48)
                applyElevateNativeFont(bold = true)
                setOnClickListener { show(entry) }
            }
            buttons[entry] = button
            tabs.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
    }

    fun attach() {
        overlay.addView(tabs, 0, LinearLayout.LayoutParams(-1, dp(48)))
        overlay.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        vm.currentLinks.observe(fragment.viewLifecycleOwner, linksChanged)
        vm.currentSubtitles.observe(fragment.viewLifecycleOwner, subtitlesChanged)
        intercept("player_sources_btt", Tab.SOURCES)
        intercept("player_tracks_btt", Tab.AUDIO)
        intercept("player_subtitle_offset_btt", Tab.SUBTITLES, true)
    }

    private fun intercept(name: String, destination: Tab, sync: Boolean = false) {
        val id = ctx.resources.getIdentifier(name, "id", ctx.packageName)
        val button = if (id == 0) null else root.findViewById<View>(id)
        button?.setOnTouchListener { view, event ->
            val handled = event.actionMasked == MotionEvent.ACTION_UP && view.isPressed &&
                event.x >= 0 && event.y >= 0 &&
                event.x < view.width && event.y < view.height && show(destination, sync)
            if (handled) view.isPressed = false
            handled
        }
        if (button != null) touches.add(button)
    }

    fun detach() {
        vm.currentLinks.removeObserver(linksChanged)
        vm.currentSubtitles.removeObserver(subtitlesChanged)
        touches.forEach { it.setOnTouchListener(null) }
        touches.clear()
        body.removeView(offsetEditor)
        overlay.removeView(tabs)
        overlay.removeView(body)
        episodeTitle?.visibility = View.VISIBLE
        episodeList?.visibility = View.VISIBLE
        seasonSelector()?.visibility = View.VISIBLE
    }

    fun isEpisodeTab() = tab == Tab.EPISODES
    fun show(destination: Tab = tab, sync: Boolean = false): Boolean {
        if (!overlay.isShown && !openNative()) return false
        tab = destination
        if (sync) showSync() else showTabContents()
        return true
    }
    fun onOpened() = showTabContents()

    private fun replaceRows(values: List<Row>) {
        rows.clear()
        rows.addAll(values)
        adapter.notifyDataSetChanged()
    }

    private fun showTabContents() {
        body.removeView(offsetEditor)
        buttons.forEach { (key, button) -> button.alpha = if (key == tab) 1f else .55f }
        val episode = tab == Tab.EPISODES
        body.visibility = if (episode) View.GONE else View.VISIBLE
        episodeList?.visibility = if (episode) View.VISIBLE else View.GONE
        episodeTitle?.visibility = if (episode && seasonSelector() == null) View.VISIBLE else View.GONE
        seasonSelector()?.visibility = if (episode) View.VISIBLE else View.GONE
        groupScroll.visibility = if (tab == Tab.SOURCES) View.VISIBLE else View.GONE
        when (tab) {
            Tab.SOURCES -> renderSources()
            Tab.AUDIO -> renderAudio()
            Tab.SUBTITLES -> renderSubtitles()
            Tab.EPISODES -> Unit
        }
    }

    private fun qualityProfile(): Int {
        val field = runCatching { FullScreenPlayer::class.java.getDeclaredField("currentQualityProfile") }
            .getOrNull()
        return runCatching {
            field?.isAccessible = true
            field?.get(fragment) as? Int
        }.getOrNull() ?: QualityDataHelper.getProfiles().firstOrNull()?.id ?: 0
    }

    private fun currentLink(): VideoLink? = runCatching {
        GeneratorPlayer::class.java.getDeclaredField("currentSelectedLink")
            .apply { isAccessible = true }.get(fragment) as? VideoLink
    }.getOrNull()

    private fun sourceGroup(link: VideoLink, api: String): String {
        // A StremioC MainAPI may be renamed to the selected addon.
        val providerClass = APIHolder.allProviders.firstOrNull { it.name == api }
            ?.javaClass?.name.orEmpty()
        val aggregate = when {
            providerClass.contains("StremioC", true) || api.contains("StremioC", true) -> "StremioC"
            providerClass.contains("StreamPlayAnime", true) -> "StreamPlay-Anime"
            providerClass.contains("StreamPlay", true) || api.contains("StreamPlay", true) -> "StreamPlay"
            providerClass.contains("CineStream", true) || api.contains("CineStream", true) -> "CineStream"
            else -> return api.ifBlank { "Unknown" }
        }
        val extractor = link.first
        val label = Normalizer.normalize(extractor?.name.orEmpty(), Normalizer.Form.NFKC)
        val name = if (aggregate == "StremioC") extractor?.source.orEmpty() else
            label.substringBefore(" [").substringBefore("[").trim()
        val child = name.takeIf { it.isNotBlank() && it.length < 65 } ?: "Other"
        return aggregate + " · " + child
    }

    private fun renderSources() {
        val source = vm.state.generatorState?.response?.apiName.orEmpty()
        val selected = currentLink()
        val entries = vm.state.sortLinks(qualityProfile())
            .filter { it.shouldUseLink || it.link == selected }.map { it.link }
        val keys = entries.map { sourceGroup(it, source) }
        val groups = keys.distinct()
        if (filter !in groups) filter = null
        filters.removeAllViews()
        fun addFilter(value: String?) {
            filters.addView(TextView(ctx).apply {
                text = value ?: "All"
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                applyElevateNativeFont(bold = value == filter)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                minimumHeight = dp(42)
                alpha = if (filter == value) 1f else .6f
                setOnClickListener { filter = value; renderSources() }
            })
        }
        addFilter(null)
        groups.forEach(::addFilter)
        replaceRows(entries.mapIndexedNotNull { index, link ->
            if (filter != null && keys[index] != filter) return@mapIndexedNotNull null
            val title = link.first?.name ?: link.second?.name ?: "Source"
            Row((if (link == selected) "✓ " else "") + title, {
                if (link != selected) runCatching {
                    GeneratorPlayer::class.java.getDeclaredMethod(
                        "loadLink", Pair::class.java, Boolean::class.javaPrimitiveType!!
                    ).apply { isAccessible = true }.invoke(fragment, link, true)
                }.onFailure { android.util.Log.w("Elevate", "Source switch failed", it) }
            })
        })
    }

    private fun renderAudio() {
        val tracks = fragment.player.getVideoTracks()
        val active = tracks.currentAudioTrack
        replaceRows(tracks.allAudioTracks.map { item ->
            val name = listOfNotNull(item.label, item.language,
                item.sampleMimeType?.substringAfterLast('/'),
                item.channelCount?.let { "$it ch" }).filter { it.isNotBlank() }
                .distinct().joinToString(" · ").ifBlank { "Audio" }
            val checked = active?.id == item.id && active?.formatIndex == item.formatIndex
            Row((if (checked) "✓ " else "") + name, {
                fragment.player.setPreferredAudioTrack(item.language, item.id, item.formatIndex)
                renderAudio()
            })
        })
    }

    private fun setSubtitle(sub: SubtitleData?) {
        runCatching {
            val requiresReload = GeneratorPlayer::class.java.getDeclaredMethod(
                "setSubtitles", SubtitleData::class.java, Boolean::class.javaPrimitiveType!!
            ).apply { isAccessible = true }.invoke(fragment, sub, true) as? Boolean == true
            if (requiresReload) {
                currentLink()?.let { link ->
                    GeneratorPlayer::class.java.getDeclaredMethod(
                        "loadLink", Pair::class.java, Boolean::class.javaPrimitiveType!!
                    ).apply { isAccessible = true }.invoke(fragment, link, true)
                }
            }
        }.onFailure { android.util.Log.w("Elevate", "Subtitle selection failed", it) }
        renderSubtitles()
    }

    private fun stremioDetails(url: String): String? = runCatching {
        val provider = APIHolder.allProviders.firstOrNull {
            it.javaClass.name == "com.hsp1020.StremioC"
        } ?: return@runCatching null
        val field = provider.javaClass.getDeclaredField("subtitleDetailByUrl")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val details = field.get(null) as? Map<String, String>
        details?.get(url)
    }.getOrNull()

    private fun renderSubtitles() {
        body.removeView(offsetEditor)
        val current = fragment.player.getCurrentPreferredSubtitle()?.url
        val output = mutableListOf(Row(if (current == null) "✓ Off" else "Off", { setSubtitle(null) }))
        sortSubs(vm.state.subtitles).groupBy { it.originalName }.forEach { (lang, group) ->
            group.sortedBy { it.nameSuffix.toIntOrNull() ?: Int.MAX_VALUE }.forEachIndexed { index, sub ->
                val detail = stremioDetails(sub.url)
                    ?: sub.nameSuffix.takeIf { it.isNotBlank() && it.toIntOrNull() == null }
                val enabled = ElevateSubtitleRefreshHook.active() != null &&
                    ElevateSubtitleRefreshRegistry.entryFor(sub) != null && sub.url == current
                val language = (if (sub.url == current) "✓ " else "") + lang
                val detailLabel = (index + 1).toString() +
                    (detail?.let { " ($it)" } ?: "")
                output.add(Row(language + " · " + detailLabel, { setSubtitle(sub) },
                    if (enabled) ({ ElevateSubtitleRefreshHook.active()?.refreshSelected(false); Unit }) else null,
                    if (enabled) ({ ElevateSubtitleRefreshHook.active()?.refreshSelected(true); Unit }) else null,
                    language, detailLabel))
            }
        }
        output.add(Row("Subtitle sync", { showSync() }))
        replaceRows(output)
    }

    private fun showSync() {
        if (offsetEditor.parent == null) body.addView(offsetEditor, 1,
            LinearLayout.LayoutParams(-1, dp(48)))
        offsetInput.setText(fragment.player.getSubtitleOffset().toString())
        tab = Tab.SUBTITLES
        buttons.forEach { (key, button) -> button.alpha = if (key == tab) 1f else .55f }
        body.visibility = View.VISIBLE
        episodeList?.visibility = View.GONE
        episodeTitle?.visibility = View.GONE
        seasonSelector()?.visibility = View.GONE
        groupScroll.visibility = View.GONE
        val output = mutableListOf(Row("Back to subtitles", { renderSubtitles() }))
        output.add(Row("Offset: " + fragment.player.getSubtitleOffset() + " ms", {}))
        for (change in listOf(-1000L, -100L, 100L, 1000L)) {
            output.add(Row((if (change > 0) "+" else "") + change + " ms", {
                fragment.player.setSubtitleOffset(fragment.player.getSubtitleOffset() + change)
                ElevateSubtitleRefreshHook.active()?.refreshAppearance()
                showSync()
            }))
        }
        output.add(Row("Reset offset", {
            fragment.player.setSubtitleOffset(0L)
            ElevateSubtitleRefreshHook.active()?.refreshAppearance()
            showSync()
        }))
        val cues = ElevateSubtitleRefreshHook.active()?.refreshedCues()
            ?: fragment.player.getSubtitleCues()
        cues.forEach { cue ->
            output.add(Row((cue.startTimeMs / 1000).toString() + "s · " +
                cue.text.joinToString(" ").take(160), {
                fragment.player.setSubtitleOffset(cue.startTimeMs -
                    (fragment.player.getPosition() ?: 0L))
                ElevateSubtitleRefreshHook.active()?.refreshAppearance()
                showSync()
            }))
        }
        replaceRows(output)
    }
}
