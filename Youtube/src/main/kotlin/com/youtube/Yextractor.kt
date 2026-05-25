package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.util.Locale

open class CustomYoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val name = "YouTube"
    override val requiresReferer = false

    init {
        YoutubeStreamExtractor.setFetchIosClient(true)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractYouTubeId(url)
        val watchUrl = "$mainUrl/watch?v=$videoId"

        val info = StreamInfo.getInfo(watchUrl)

        val isLive =
            info.streamType == StreamType.LIVE_STREAM
                    || info.streamType == StreamType.AUDIO_LIVE_STREAM
                    || info.streamType == StreamType.POST_LIVE_STREAM
                    || info.streamType == StreamType.POST_LIVE_AUDIO_STREAM

        if (isLive && info.hlsUrl != null) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube Live",
                    url = info.hlsUrl
                ) {
                    type = ExtractorLinkType.M3U8
                }
            )
        } else {
            processVideo(info, subtitleCallback, callback)
        }
    }

    private suspend fun processVideo(
        info: StreamInfo,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val videoStreams = info.videoOnlyStreams.orEmpty()
        val audioStreams = info.audioStreams.orEmpty()
        val muxedStreams = info.videoStreams.orEmpty()

        if (videoStreams.isEmpty() && muxedStreams.isEmpty()) return false

        // 1. 영상+오디오 통합본 백업 (Muxed)
        muxedStreams.forEach { video ->
            val vUrl = video.content ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube ${normalizeCodec(video.codec)} (Muxed)",
                    url = vUrl
                ) {
                    quality = video.height ?: 0
                }
            )
        }

        // 2. 🔥 오디오 언어별로 각각 독립된 서버(ExtractorLink)를 콜백으로 날려줍니다!
        videoStreams.forEach { video ->
            val vUrl = video.content ?: return@forEach
            val height = video.height ?: 0
            val codecName = normalizeCodec(video.codec)

            if (audioStreams.isEmpty()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "YouTube $codecName",
                        url = vUrl
                    ) {
                        quality = height
                    }
                )
            } else {
                // 오디오 스트림(언어) 개수만큼 서버 선택 링크를 증식시킵니다.
                audioStreams.forEach { audio ->
                    val aUrl = audio.content ?: return@forEach
                    
                    // 언어 정보 가져오기 (한국어 번역 우선)
                    val displayName = audio.audioLocale?.getDisplayLanguage(Locale.KOREAN) 
                        ?: audio.audioLocale?.displayName 
                        ?: audio.audioTrackId 
                        ?: "Audio"

                    // source 에 언어를 달아주어 사용자가 서버 목록에서 언어를 바로 고르게 합니다.
                    callback(
                        newExtractorLink(
                            source = "$name ($displayName)", 
                            name = "YouTube $codecName",
                            url = vUrl
                        ) {
                            quality = height
                            // 코어의 한계(URL만 받음)를 지키면서 해당 언어의 오디오 1개만 주입합니다.
                            audioTracks = listOf(newAudioFile(aUrl))
                        }
                    )
                }
            }
        }

        // 3. 자막 처리
        info.subtitles.orEmpty().forEach { subtitle ->
            val subUrl = subtitle.content ?: return@forEach
            subtitleCallback(
                newSubtitleFile(
                    lang = subtitle.displayLanguageName ?: subtitle.languageTag ?: "Unknown",
                    url = subUrl
                )
            )
        }

        return true
    }

    // ---------------- HELPERS ----------------

    private fun extractYouTubeId(url: String): String {
        val regex = Regex(
            "(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:.*v=|v/|u/\\w/|embed/|shorts/|live/))([\\w-]{11})"
        )
        return regex.find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid YouTube URL: $url")
    }

    private fun normalizeCodec(codec: String?): String {
        if (codec.isNullOrBlank()) return ""

        val c = codec.lowercase()

        return when {
            c.startsWith("av01") -> "AV1"
            c.startsWith("vp9") -> "VP9"
            c.startsWith("avc1") || c.startsWith("h264") -> "H264"
            c.startsWith("hev1") || c.startsWith("hvc1") || c.startsWith("hevc") -> "H265"
            else -> codec.substringBefore('.').uppercase()
        }
    }
}
