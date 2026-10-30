package com.hsp1020.elevate

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.text.TextWatcher
import android.text.Editable
import android.text.TextUtils
import android.widget.TextView
import android.util.TypedValue
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerGeneratorViewModel
import com.lagradost.cloudstream3.ui.player.VideoLink
import com.lagradost.cloudstream3.ui.player.VideoLive
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.player.SubtitleOffsetItemAdapter
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
        val richText: Boolean = false)
    private val ctx = overlay.context
    private val dp: (Int) -> Int = { (it * ctx.resources.displayMetrics.density + .5f).toInt() }
    private val textColor = episodeTitle?.currentTextColor ?: Color.WHITE
    private var nativeWidth = overlay.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
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
        choiceMode = AbsListView.CHOICE_MODE_SINGLE
        divider = null
        selector = ColorDrawable(Color.TRANSPARENT)
    }
    private val adapter = object : ArrayAdapter<Row>(ctx, android.R.layout.simple_list_item_1, rows) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val value = getItem(position) ?: return TextView(ctx)
            val selected = (parent as? ListView)?.isItemChecked(position) == true
            // Native CheckLabel is the same row used by CloudStream's subtitle/source picker.
            if (value.refresh == null) {
                val text = (convertView as? TextView)
                    ?: (LayoutInflater.from(ctx).inflate(
                        R.layout.sort_bottom_single_choice, parent, false
                    ) as TextView)
                text.text = if (value.richText) value.label.html() else value.label
                text.maxLines = Int.MAX_VALUE
                text.ellipsize = null
                text.isActivated = selected
                return text
            }
            val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                addView(LayoutInflater.from(ctx).inflate(
                    R.layout.sort_bottom_single_choice, this, false
                ), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageButton(ctx).apply {
                    setImageResource(android.R.drawable.ic_popup_sync)
                    contentDescription = "Refresh subtitle"
                    background = null
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    isFocusable = true
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            (row.getChildAt(0) as TextView).apply {
                text = if (value.richText) value.label.html() else value.label
                maxLines = Int.MAX_VALUE
                ellipsize = null
                isActivated = selected
                setOnClickListener {
                    (parent as? ListView)?.performItemClick(row, position, getItemId(position))
                }
            }
            (row.getChildAt(1) as ImageButton).apply {
                visibility = View.VISIBLE
                setOnClickListener { value.refresh?.invoke() }
                setOnLongClickListener {
                    value.autoRefresh?.invoke()
                    true
                }
            }
            return row
        }
    }
    private val languageAdapter = ArrayAdapter<CharSequence>(
        ctx, R.layout.sort_bottom_single_choice, ArrayList()
    )
    private var subtitleGroups = emptyList<Pair<String, List<SubtitleData>>>()
    private var selectedGroup = 0
    private val languageList = ListView(ctx).apply {
        choiceMode = AbsListView.CHOICE_MODE_SINGLE
        divider = null
        adapter = languageAdapter
        setOnItemClickListener { _, _, position, _ ->
            selectedGroup = position
            setItemChecked(position, true)
            if (position == 0) setSubtitle(null)
            else {
                val group = subtitleGroups.getOrNull(position - 1)?.second
                if (group?.size == 1) setSubtitle(group.first())
                else renderSubtitleOptions()
            }
        }
    }
    private val subtitlePane = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(languageList, LinearLayout.LayoutParams(0, -1, .35f))
    }
    private var inSync = false
    private val syncButton = TextView(ctx).apply {
        text = ctx.getString(R.string.subtitle_offset_title)
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        minimumHeight = dp(44)
        applyElevateNativeFont(bold = true)
        setOnClickListener { if (inSync) showTabContents() else showSync() }
        visibility = View.GONE
    }
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(groupScroll, LinearLayout.LayoutParams(-1, dp(56)))
        addView(syncButton, LinearLayout.LayoutParams(-1, dp(44)))
        addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private var tab = Tab.EPISODES
    private var filter: String? = null
    // Inflate the actual CloudStream subtitle_offset layout, preserving its TextViews,
    // buttons, cue adapter and localized resources. Only reflow its two containers
    // vertically to fit the narrow right-hand episode panel.
    private val nativeSync = LayoutInflater.from(ctx).inflate(
        R.layout.subtitle_offset, body, false
    ) as LinearLayout
    private val offsetInput = nativeSync.findViewById<android.widget.EditText>(R.id.subtitle_offset_input)
    private val offsetHint = nativeSync.findViewById<TextView>(R.id.subtitle_offset_sub_title)
    private val syncList = nativeSync.findViewById<RecyclerView>(R.id.subtitle_offset_recyclerview)
    private val noCues = nativeSync.findViewById<TextView>(R.id.no_subtitles_loaded_notice)
    private val syncAdapter = SubtitleOffsetItemAdapter(0L) { cue ->
        offsetInput.setText((cue.startTimeMs - (fragment.player.getPosition() ?: 0L)).toString())
    }
    private val syncOffsetWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val amount = s?.toString()?.toLongOrNull() ?: return
            val time = (fragment.player.getPosition() ?: 0L) + amount
            offsetHint.text = when {
                amount > 0 -> ctx.getString(R.string.subtitle_offset_extra_hint_before_format, amount)
                amount < 0 -> ctx.getString(R.string.subtitle_offset_extra_hint_later_format, -amount)
                else -> ctx.getString(R.string.subtitle_offset_extra_hint_none_format)
            }
            syncAdapter.updateTime(time)
            syncList.scrollToPosition(syncAdapter.getLatestActiveItem(time))
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun configureNativeSync() {
        val cueContainer = nativeSync.getChildAt(0)
        val controls = nativeSync.getChildAt(1) as LinearLayout
        nativeSync.removeAllViews()
        nativeSync.orientation = LinearLayout.VERTICAL
        nativeSync.addView(controls, LinearLayout.LayoutParams(-1, -2))
        nativeSync.addView(cueContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        controls.layoutParams = LinearLayout.LayoutParams(-1, -2)
        controls.getChildAt(0).layoutParams = LinearLayout.LayoutParams(-1, -2)
        offsetInput.addTextChangedListener(syncOffsetWatcher)
        syncList.adapter = syncAdapter
        (syncList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        fun increment(id: Int, millis: Long) {
            nativeSync.findViewById<View>(id)?.setOnClickListener {
                val current = offsetInput.text.toString().toLongOrNull() ?: 0L
                offsetInput.setText((current + millis).toString())
            }
        }
        increment(R.id.subtitle_offset_subtract, -100L)
        increment(R.id.subtitle_offset_subtract_more, -1000L)
        increment(R.id.subtitle_offset_add, 100L)
        increment(R.id.subtitle_offset_add_more, 1000L)
        nativeSync.findViewById<View>(R.id.apply_btt)?.setOnClickListener {
            offsetInput.text.toString().toLongOrNull()?.let {
                fragment.player.setSubtitleOffset(it)
                ElevateSubtitleRefreshHook.active()?.refreshAppearance()
                showTabContents()
            }
        }
        nativeSync.findViewById<View>(R.id.reset_btt)?.setOnClickListener {
            fragment.player.setSubtitleOffset(0L)
            ElevateSubtitleRefreshHook.active()?.refreshAppearance()
            showTabContents()
        }
        nativeSync.findViewById<View>(R.id.cancel_btt)?.setOnClickListener {
            showTabContents()
        }
    }
    private val touches = ArrayList<View>()
    private val linksChanged = Observer<VideoLive<Set<VideoLink>>> {
        if (tab == Tab.SOURCES) showTabContents()
    }
    private val subtitlesChanged = Observer<VideoLive<Set<SubtitleData>>> {
        if (tab == Tab.SUBTITLES) showTabContents()
    }

    init {
        configureNativeSync()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.action?.invoke() }
        for (entry in Tab.entries) {
            val button = TextView(ctx).apply {
                text = when (entry) {
                    Tab.SOURCES -> ctx.getString(R.string.video_source)
                    Tab.AUDIO -> ctx.getString(R.string.audio_tracks)
                    Tab.SUBTITLES -> ctx.getString(R.string.pick_subtitle)
                    Tab.EPISODES -> ctx.getString(R.string.episodes)
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
        nativeWidth = overlay.layoutParams.width
        overlay.layoutParams = overlay.layoutParams.apply { width = dp(360) }
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
        overlay.layoutParams = overlay.layoutParams.apply { width = nativeWidth }
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

    private fun panelContent(view: View) {
        if (body.getChildAt(body.childCount - 1) === view) return
        (list.parent as? ViewGroup)?.removeView(list)
        (subtitlePane.parent as? ViewGroup)?.removeView(subtitlePane)
        (nativeSync.parent as? ViewGroup)?.removeView(nativeSync)
        if (view === subtitlePane) {
            subtitlePane.addView(list, LinearLayout.LayoutParams(0, -1, .65f))
        }
        body.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showTabContents() {
        inSync = false
        buttons.forEach { (key, button) -> button.alpha = if (key == tab) 1f else .55f }
        val episode = tab == Tab.EPISODES
        body.visibility = if (episode) View.GONE else View.VISIBLE
        episodeList?.visibility = if (episode) View.VISIBLE else View.GONE
        episodeTitle?.visibility = if (episode && seasonSelector() == null) View.VISIBLE else View.GONE
        seasonSelector()?.visibility = if (episode) View.VISIBLE else View.GONE
        groupScroll.visibility = if (tab == Tab.SOURCES) View.VISIBLE else View.GONE
        syncButton.visibility = if (tab == Tab.SUBTITLES) View.VISIBLE else View.GONE
        syncButton.text = ctx.getString(R.string.subtitle_offset_title)
        when (tab) {
            Tab.SOURCES -> { panelContent(list); renderSources() }
            Tab.AUDIO -> { panelContent(list); renderAudio() }
            Tab.SUBTITLES -> { panelContent(subtitlePane); renderSubtitles() }
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
            val label = LayoutInflater.from(ctx).inflate(
                R.layout.sort_bottom_single_choice, filters, false
            ) as TextView
            label.apply {
                text = value ?: ctx.getString(R.string.all)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(184)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                minimumHeight = dp(52)
                isActivated = (value == filter)
                alpha = if (value == filter) 1f else .65f
                setOnClickListener { filter = value; renderSources() }
            }
            filters.addView(label, LinearLayout.LayoutParams(-2, -2))
        }
        addFilter(null)
        groups.forEach(::addFilter)
        replaceRows(entries.mapIndexedNotNull { index, link ->
            if (filter != null && keys[index] != filter) return@mapIndexedNotNull null
            val name = link.first?.name ?: link.second?.name ?: "Source"
            val title = "$name ${Qualities.getStringByInt(link.first?.quality)}"
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
            val language = item.language?.trim()?.let { raw ->
                fromTagToLanguageName(raw)
                    ?: fromTagToLanguageName(raw.replace('_', '-').substringBefore('-').lowercase())
                    ?: raw
            } ?: item.label ?: "Audio"
            val channels = when (item.channelCount) {
                1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
                null, 0 -> ""
                else -> "${item.channelCount}ch"
            }
            val mime = item.sampleMimeType.orEmpty().lowercase()
            val codec = when {
                "eac3-joc" in mime -> "Dolby Atmos"
                "truehd" in mime -> "TrueHD"
                "eac3" in mime -> "E-AC3"
                "ac-3" in mime || "ac3" in mime -> "AC3"
                "aac" in mime || "mp4a" in mime -> "AAC"
                "opus" in mime -> "Opus"
                "vorbis" in mime -> "Vorbis"
                "mp3" in mime -> "MP3"
                else -> item.sampleMimeType?.substringAfter('/')?.uppercase().orEmpty()
            }
            val name = listOf(language.replaceFirstChar { it.uppercaseChar() }, channels, codec)
                .filter { it.isNotBlank() }.joinToString(" • ")
            val checked = active?.id == item.id && active?.formatIndex == item.formatIndex
            Row((if (checked) "✓  " else "    ") + name, {
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

    private fun orderedOptions(group: List<SubtitleData>): List<SubtitleData> =
        group.sortedBy { it.nameSuffix.toIntOrNull() ?: 0 }
            .withIndex().sortedWith(
                compareBy<IndexedValue<SubtitleData>> {
                    if (it.value.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) -1 else 40_000
                }.thenBy { it.index }
            ).map { it.value }

    private fun renderSubtitles() {
        val current = fragment.player.getCurrentPreferredSubtitle()
        subtitleGroups = sortSubs(vm.state.subtitles)
            .groupBy { it.originalName }
            .map { (name, subtitles) -> name to orderedOptions(subtitles) }
        selectedGroup = subtitleGroups.indexOfFirst { (name, _) ->
            name == current?.originalName
        }.let { if (it < 0) 0 else it + 1 }
        languageAdapter.clear()
        languageAdapter.add(ctx.getString(R.string.no_subtitles).html())
        subtitleGroups.forEach { (language, _) -> languageAdapter.add(language.html()) }
        languageList.setItemChecked(selectedGroup, true)
        languageList.setSelection(selectedGroup)
        renderSubtitleOptions()
    }

    private fun renderSubtitleOptions() {
        val current = fragment.player.getCurrentPreferredSubtitle()?.url
        val group = subtitleGroups.getOrNull(selectedGroup - 1)?.second.orEmpty()
        replaceRows(group.mapIndexed { index, sub ->
            val number = index + 1 // Visual option index, not nameSuffix (native picker behavior).
            val label = if (sub.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) {
                val embedded = ctx.getString(R.string.subtitles_from_embedded)
                "$number ($embedded" +
                    (if (sub.nameSuffix.isNotBlank()) " · ${sub.nameSuffix}" else "") + ")"
            } else {
                val detail = sub.nameSuffix.takeIf {
                    it.isNotBlank() && it.toIntOrNull() == null
                }
                "$number" + (detail?.let { "($it)" } ?: "")
            }
            val refreshable = sub.url == current &&
                ElevateSubtitleRefreshHook.active() != null &&
                ElevateSubtitleRefreshRegistry.entryFor(sub) != null
            Row(label, { setSubtitle(sub) },
                if (refreshable) ({ ElevateSubtitleRefreshHook.active()?.refreshSelected(false); Unit }) else null,
                if (refreshable) ({ ElevateSubtitleRefreshHook.active()?.refreshSelected(true); Unit }) else null,
                richText = true)
        })
        val selected = group.indexOfFirst { it.url == current }
        if (selected >= 0) {
            list.setItemChecked(selected, true)
            list.setSelection(selected)
        } else list.clearChoices()
    }

    private fun showSync() {
        tab = Tab.SUBTITLES
        inSync = true
        buttons.forEach { (key, button) -> button.alpha = if (key == tab) 1f else .55f }
        body.visibility = View.VISIBLE
        episodeList?.visibility = View.GONE
        episodeTitle?.visibility = View.GONE
        seasonSelector()?.visibility = View.GONE
        groupScroll.visibility = View.GONE
        syncButton.visibility = View.VISIBLE
        syncButton.text = "‹ ${ctx.getString(R.string.pick_subtitle)}"
        panelContent(nativeSync)
        offsetInput.setText(fragment.player.getSubtitleOffset().toString())
        val cues = ElevateSubtitleRefreshHook.active()?.refreshedCues()
            ?: fragment.player.getSubtitleCues()
        syncList.visibility = if (cues.isEmpty()) View.GONE else View.VISIBLE
        noCues.visibility = if (cues.isEmpty()) View.VISIBLE else View.GONE
        val target = (fragment.player.getPosition() ?: 0L) + fragment.player.getSubtitleOffset()
        syncAdapter.submitList(cues)
        syncList.post {
            if (inSync) syncList.scrollToPosition(syncAdapter.getLatestActiveItem(target))
        }
        syncAdapter.updateTime(target)
    }

}
