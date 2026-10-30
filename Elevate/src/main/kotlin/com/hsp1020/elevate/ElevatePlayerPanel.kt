package com.hsp1020.elevate

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
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
import com.lagradost.cloudstream3.ui.player.AudioTrack
import com.lagradost.cloudstream3.ui.player.VideoTrack
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
        val refresh: ((ImageButton) -> Unit)? = null, val autoRefresh: ((ImageButton) -> Unit)? = null,
        val richText: Boolean = false, val selected: Boolean = false,
        val header: Boolean = false, val refreshUrl: String? = null)
    private val ctx = overlay.context
    private val dp: (Int) -> Int = { (it * ctx.resources.displayMetrics.density + .5f).toInt() }
    private val textColor = episodeTitle?.currentTextColor ?: Color.WHITE
    private val standardTextColor by lazy {
        (LayoutInflater.from(ctx).inflate(R.layout.sort_bottom_single_choice, null, false) as TextView).textColors
    }
    private var chosenLink: VideoLink? = null
    private var chosenAudio: AudioTrack? = null
    private var chosenVideo: VideoTrack? = null
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
        choiceMode = AbsListView.CHOICE_MODE_NONE
        divider = null
        selector = ColorDrawable(Color.TRANSPARENT)
    }
    private fun styleRow(text: TextView, selected: Boolean, header: Boolean = false) {
        // CheckLabel defines drawableStartCompat (24dp) plus a drawable gap.
        // Clear it and reserve the checkmark in an independent fixed-width view.
        text.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        text.compoundDrawablePadding = 0
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        text.applyElevateNativeFont(reference = buttons.values.firstOrNull()?.typeface ?: text.typeface,
            bold = true)
        text.setPadding(0, dp(2), 0, dp(2))
        text.minHeight = 0
        text.includeFontPadding = false
        text.gravity = if (header) Gravity.CENTER else Gravity.CENTER_VERTICAL
        text.setTextColor(if (selected) ColorStateList.valueOf(Color.WHITE) else standardTextColor)
        text.isActivated = selected
        text.isSelected = false
    }

    private fun bindRow(
        reuse: View?, parent: ViewGroup, position: Int, label: CharSequence, checked: Boolean,
        compact: Boolean, refresh: ((ImageButton) -> Unit)? = null,
        longRefresh: ((ImageButton) -> Unit)? = null, refreshUrl: String? = null
    ): View {
        val row = (reuse as? LinearLayout)?.takeIf { it.childCount == 3 }
            ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_baseline_check_24_listview)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
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
        row.layoutParams = AbsListView.LayoutParams(-1, if (compact) dp(48) else -2)
        row.minimumHeight = if (compact) dp(48) else dp(28)
        (row.getChildAt(0) as ImageView).apply {
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = if (checked) View.VISIBLE else View.INVISIBLE
        }
        (row.getChildAt(1) as TextView).apply {
            text = label
            styleRow(this, checked)
            maxLines = if (compact) 2 else Int.MAX_VALUE
            ellipsize = if (compact) TextUtils.TruncateAt.END else null
            setOnClickListener {
                (parent as? ListView)?.performItemClick(row, position, position.toLong())
            }
        }
        (row.getChildAt(2) as ImageButton).apply {
            visibility = if (refresh != null) View.VISIBLE else View.GONE
            tag = refreshUrl
            setOnClickListener(if (refresh != null) View.OnClickListener { refresh(this) } else null)
            setOnLongClickListener(if (longRefresh != null) View.OnLongClickListener {
                longRefresh(this)
                true
            } else null)
        }
        return row
    }

    private val adapter = object : ArrayAdapter<Row>(ctx, android.R.layout.simple_list_item_1, rows) {
        override fun isEnabled(position: Int): Boolean = getItem(position)?.header != true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val value = getItem(position) ?: return View(ctx)
            if (value.header) {
                val text = (convertView as? TextView)
                    ?: (LayoutInflater.from(ctx).inflate(
                        R.layout.sort_bottom_single_choice, parent, false
                    ) as TextView)
                text.text = value.label
                styleRow(text, false, true)
                text.minHeight = dp(if (value.label.isEmpty()) 48 else 36)
                return text
            }
            return bindRow(convertView, parent, position,
                if (value.richText) value.label.html() else value.label,
                value.selected, tab == Tab.SUBTITLES, value.refresh, value.autoRefresh,
                value.refreshUrl)
        }
    }
    private val languageAdapter = object : ArrayAdapter<CharSequence>(
        ctx, R.layout.sort_bottom_single_choice, ArrayList()
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bindRow(convertView, parent, position, getItem(position) ?: "",
                position == selectedGroup, compact = true)
    }
    private var subtitleGroups = emptyList<Pair<String, List<SubtitleData>>>()
    private var selectedGroup = 0
    private var selectedSubtitle: SubtitleData? = null
    private var selectionInitialized = false
    private val languageList = ListView(ctx).apply {
        choiceMode = AbsListView.CHOICE_MODE_NONE
        divider = null
        adapter = languageAdapter
        setOnItemClickListener { _, _, position, _ ->
            selectedGroup = position
            languageAdapter.notifyDataSetChanged()
            if (position == 0) setSubtitle(null)
            else {
                val group = subtitleGroups.getOrNull(position - 1)?.second.orEmpty()
                val preferred = group.firstOrNull { it.url == selectedSubtitle?.url }
                val chosen = preferred ?: group.firstOrNull()
                if (chosen != null) setSubtitle(chosen) else renderSubtitleOptions()
            }
        }
    }
    private val subtitlePane = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(languageList, LinearLayout.LayoutParams(0, -1, .35f))
    }
    private var inSync = false
    private fun arrow(reverse: Boolean) = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_baseline_arrow_back_ios_24)
        imageTintList = ColorStateList.valueOf(textColor)
        if (reverse) rotationY = 180f
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private val syncBack = arrow(false)
    private val syncForward = arrow(true)
    private val syncLabel = TextView(ctx).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        applyElevateNativeFont(bold = true)
    }
    private val syncButton = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        orientation = LinearLayout.HORIZONTAL
        minimumHeight = dp(44)
        addView(syncBack, LinearLayout.LayoutParams(dp(14), dp(14)))
        addView(syncLabel, LinearLayout.LayoutParams(-2, -1).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        addView(syncForward, LinearLayout.LayoutParams(dp(14), dp(14)))
        setOnClickListener { if (inSync) showTabContents() else showSync() }
        visibility = View.GONE
    }
    private fun updateSyncHeader(back: Boolean) {
        syncBack.visibility = if (back) View.VISIBLE else View.GONE
        syncForward.visibility = if (back) View.GONE else View.VISIBLE
        syncLabel.text = ctx.getString(if (back) R.string.pick_subtitle
            else R.string.subtitle_offset_title)
    }
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(groupScroll, LinearLayout.LayoutParams(-1, dp(42)))
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
        nativeSync.addView(cueContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        nativeSync.addView(controls, LinearLayout.LayoutParams(-1, -2))
        controls.layoutParams = LinearLayout.LayoutParams(-1, -2)
        controls.getChildAt(0).layoutParams = LinearLayout.LayoutParams(-1, -2)
        controls.getChildAt(0).layoutParams = (controls.getChildAt(0).layoutParams
            as LinearLayout.LayoutParams).apply { width = -1 }
        fun matchTabTextSize(view: View) {
            if (view is TextView) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            if (view is ViewGroup && view !is RecyclerView) {
                for (i in 0 until view.childCount) matchTabTextSize(view.getChildAt(i))
            }
        }
        matchTabTextSize(controls)
        syncList.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.findViewById<TextView>(R.id.subtitle_text)
                    ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
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
        list.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position)?.takeUnless { it.header }?.action?.invoke()
        }
        for (entry in Tab.entries) {
            val button = TextView(ctx).apply {
                text = when (entry) {
                    Tab.SOURCES -> ctx.getString(R.string.video_source)
                    Tab.AUDIO -> ctx.getString(R.string.tracks)
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
        if (destination != tab) {
            if (destination == Tab.SOURCES) chosenLink = null
            if (destination == Tab.AUDIO) {
                chosenAudio = null
                chosenVideo = null
            }
        }
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
        updateSyncHeader(false)
        when (tab) {
            Tab.SOURCES -> { panelContent(list); renderSources() }
            Tab.AUDIO -> { panelContent(list); renderTracks() }
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
        val selected = chosenLink ?: currentLink()
        val entries = vm.state.sortLinks(qualityProfile())
            .filter { it.shouldUseLink || it.link == selected }.map { it.link }
        val keys = entries.map { sourceGroup(it, source) }
        val groups = keys.distinct()
        if (filter !in groups) filter = null
        filters.removeAllViews()
        fun addFilter(value: String?) {
            val item = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_baseline_check_24_listview)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    visibility = if (value == filter) View.VISIBLE else View.INVISIBLE
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView((LayoutInflater.from(ctx).inflate(
                    R.layout.sort_bottom_single_choice, this, false
                ) as TextView).apply {
                    text = value ?: ctx.getString(R.string.all)
                    styleRow(this, value == filter)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(164)
                }, LinearLayout.LayoutParams(-2, -2))
                minimumHeight = dp(36)
                alpha = if (value == filter) 1f else .65f
                setOnClickListener { filter = value; renderSources() }
            }
            filters.addView(item, LinearLayout.LayoutParams(-2, -2))
        }
        addFilter(null)
        groups.forEach(::addFilter)
        replaceRows(entries.mapIndexedNotNull { index, link ->
            if (filter != null && keys[index] != filter) return@mapIndexedNotNull null
            val name = link.first?.name ?: link.second?.name ?: "Source"
            val title = "$name ${Qualities.getStringByInt(link.first?.quality)}"
            Row(title, {
                if (link != selected) runCatching {
                    GeneratorPlayer::class.java.getDeclaredMethod(
                        "loadLink", Pair::class.java, Boolean::class.javaPrimitiveType!!
                    ).apply { isAccessible = true }.invoke(fragment, link, true)
                    chosenLink = link
                    renderSources()
                }.onFailure { android.util.Log.w("Elevate", "Source switch failed", it) }
            }, selected = link == selected)
        })
    }

    private fun renderTracks() {
        val tracks = fragment.player.getVideoTracks()
        val activeAudio = chosenAudio ?: tracks.currentAudioTrack
        val activeVideo = chosenVideo ?: tracks.currentVideoTrack
        val audio = tracks.allAudioTracks
        val video = tracks.allVideoTracks.sortedBy { it.height?.times(-1) }
        val items = ArrayList<Row>()
        items += Row(ctx.getString(R.string.audio_tracks), {}, header = true)
        audio.forEach { item ->
            val language = item.language?.trim()?.let { raw ->
                fromTagToLanguageName(raw)
                    ?: fromTagToLanguageName(raw.replace('_', '-').substringBefore('-').lowercase())
                    ?: raw
            } ?: item.label ?: ctx.getString(R.string.audio_tracks)
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
            val label = listOf(language.replaceFirstChar { it.uppercaseChar() }, channels, codec)
                .filter { it.isNotBlank() }.joinToString(" • ")
            val checked = activeAudio == item
            items += Row(label, {
                fragment.player.setPreferredAudioTrack(item.language, item.id, item.formatIndex)
                chosenAudio = item
                renderTracks()
            }, selected = checked)
        }
        items += Row(ctx.getString(R.string.video_tracks), {}, header = true)
        video.forEachIndexed { index, item ->
            val width = item.width ?: -1
            val height = item.height ?: -1
            val title = item.label ?: if (width > 0 && height > 0) "${width}x${height}"
                else (index + 1).toString()
            val checked = activeVideo == item
            items += Row(title, {
                if (width > 0 && height > 0) {
                    fragment.player.setMaxVideoSize(width, height, item.id)
                    chosenVideo = item
                    renderTracks()
                }
            }, selected = checked)
        }
        replaceRows(items)
    }

    private fun setSubtitle(sub: SubtitleData?) {
        runCatching {
            val requiresReload = GeneratorPlayer::class.java.getDeclaredMethod(
                "setSubtitles", SubtitleData::class.java, Boolean::class.javaPrimitiveType!!
            ).apply { isAccessible = true }.invoke(fragment, sub, true) as? Boolean == true
            selectedSubtitle = sub
            selectionInitialized = true
            if (requiresReload) {
                currentLink()?.let { link ->
                    GeneratorPlayer::class.java.getDeclaredMethod(
                        "loadLink", Pair::class.java, Boolean::class.javaPrimitiveType!!
                    ).apply { isAccessible = true }.invoke(fragment, link, true)
                }
            }
        }.onFailure { android.util.Log.w("Elevate", "Subtitle selection failed", it) }
            .onSuccess { renderSubtitles() }
    }

    private fun orderedOptions(group: List<SubtitleData>): List<SubtitleData> =
        group.sortedBy { it.nameSuffix.toIntOrNull() ?: 0 }
            .withIndex().sortedWith(
                compareBy<IndexedValue<SubtitleData>> {
                    if (it.value.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) -1 else 40_000
                }.thenBy { it.index }
            ).map { it.value }

    private fun renderSubtitles() {
        if (!selectionInitialized) {
            selectedSubtitle = fragment.player.getCurrentPreferredSubtitle()
            selectionInitialized = true
        }
        val current = selectedSubtitle
        subtitleGroups = sortSubs(vm.state.subtitles)
            .groupBy { it.originalName }
            .map { (name, subtitles) -> name to orderedOptions(subtitles) }
        selectedGroup = subtitleGroups.indexOfFirst { (name, _) ->
            name == current?.originalName
        }.let { if (it < 0) 0 else it + 1 }
        languageAdapter.clear()
        languageAdapter.add(ctx.getString(R.string.no_subtitles).html())
        subtitleGroups.forEach { (language, _) -> languageAdapter.add(language.html()) }
        languageAdapter.notifyDataSetChanged()
        renderSubtitleOptions()
    }

    private fun renderSubtitleOptions() {
        val current = selectedSubtitle?.url
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
                if (refreshable) ({ button: ImageButton ->
                    ElevateSubtitleRefreshHook.active()?.refreshSelected(false, button)
                    Unit
                }) else null,
                if (refreshable) ({ button: ImageButton ->
                    ElevateSubtitleRefreshHook.active()?.refreshSelected(true, button)
                    Unit
                }) else null,
                richText = true, selected = sub.url == current, refreshUrl = sub.url)
        })
        // Both lists now start at the same top edge with identical 48dp rows.
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
        updateSyncHeader(true)
        panelContent(nativeSync)
        // Align the bottom edge of the native sync buttons just above the
        // actual player bar, rather than pinning them to the screen bottom.
        nativeSync.post {
            val playerBar = root.findViewById<View>(R.id.bottom_player_bar)
            if (inSync && playerBar != null && playerBar.isShown && nativeSync.height > 0) {
                val syncOnScreen = IntArray(2)
                val barOnScreen = IntArray(2)
                nativeSync.getLocationOnScreen(syncOnScreen)
                playerBar.getLocationOnScreen(barOnScreen)
                val bottom = syncOnScreen[1] + nativeSync.height
                val inset = (bottom - barOnScreen[1]).coerceAtLeast(0) + dp(4)
                if (nativeSync.paddingBottom != inset) {
                    nativeSync.setPadding(0, 0, 0, inset)
                }
            }
        }
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
