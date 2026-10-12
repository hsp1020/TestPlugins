package com.hsp1020

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.VideoGenerator
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Player-only playlist. None of these trailers become StremioC seasons or episodes.
 * ResultEpisode metadata enables CloudStream's built-in title, episode list and next controls.
 */
internal class TrailerPlaybackGenerator(
    private val trailers: List<TrailerChoice>,
    heading: String,
    selectedIndex: Int,
    selectedLinks: List<ExtractorLink>,
    selectedSubtitles: List<SubtitleData>
) : VideoGenerator<ResultEpisode>(
    trailers.mapIndexed { index, item ->
        ResultEpisode(
            headerName = heading,
            name = item.title,
            poster = "https://i.ytimg.com/vi/${item.youtubeId}/hqdefault.jpg",
            episode = index + 1,
            seasonIndex = null,
            season = null,
            data = "https://www.youtube.com/watch?v=${item.youtubeId}",
            apiName = "StremioC",
            id = -(("stremioc-trailer:" + item.youtubeId).hashCode() and Int.MAX_VALUE) - 1,
            index = index,
            position = 0L,
            duration = 0L,
            score = null,
            description = null,
            isFiller = null,
            tvType = TvType.Others,
            parentId = -(heading.hashCode() and Int.MAX_VALUE) - 1,
            videoWatchState = VideoWatchState.None
        )
    }
) {
    override val hasCache = false
    override val canSkipLoading = false
    private val cache = mutableMapOf(
        selectedIndex to (selectedLinks to selectedSubtitles)
    )

    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean
    ): Boolean {
        val trailer = trailers.getOrNull(offset) ?: return false
        if (clearCache) cache.remove(offset)
        val (links, subtitles) = cache[offset] ?: withContext(Dispatchers.IO) {
            val extractedLinks = mutableListOf<ExtractorLink>()
            val extractedSubs = mutableListOf<SubtitleFile>()
            com.hsp1020.youtube.YoutubeExtractor().getUrl(
                "https://www.youtube.com/watch?v=${trailer.youtubeId}",
                null,
                { extractedSubs.add(it) },
                { extractedLinks.add(it) }
            )
            extractedLinks.filter {
                it.source == "YouTube" && it.type in setOf(
                    ExtractorLinkType.VIDEO, ExtractorLinkType.DASH, ExtractorLinkType.M3U8
                )
            }.distinctBy { it.url } to extractedSubs.distinctBy { it.url }
                .map { PlayerSubtitleHelper.getSubtitleData(it) }
        }.also { cache[offset] = it }

        links.filter { it.type in sourceTypes }.forEach { callback(it to null) }
        subtitles.forEach(subtitleCallback)
        return links.any { it.type in sourceTypes }
    }
}
