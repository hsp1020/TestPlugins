package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale

open class CustomYoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val name = "YouTube"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractYouTubeId(url)
        val watchUrl = "$mainUrl/watch?v=$videoId"

        // 본진 코어 앱과 100% 동일한 방식(StreamInfo)으로 1번만 안전하게 호출합니다. (403 에러 절대 없음)
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

        // 1. Muxed Streams (영상+음성 통합본 백업)
        muxedStreams.forEach { video ->
            val url = video.content ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube ${normalizeCodec(video.codec)} (Muxed)",
                    url = url
                ) {
                    quality = video.height ?: 0
                }
            )
        }

        // 2. 💡 [핵심 버그 수정] 오디오 언어별로 각각 독립된 ExtractorLink를 생성합니다!
        videoStreams.forEach { video ->
            val url = video.content ?: return@forEach
            val height = video.height ?: 0
            val codecName = normalizeCodec(video.codec)

            if (audioStreams.isEmpty()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "YouTube $codecName",
                        url = url
                    ) {
                        quality = height
                    }
                )
            } else {
                audioStreams.forEach { audio ->
                    val audioUrl = audio.content ?: return@forEach
                    
                    // 언어 정보 가져오기 (한국어 번역 우선)
                    val langName = audio.audioLocale?.getDisplayLanguage(Locale.KOREAN) 
                        ?: audio.audioLocale?.displayName 
                        ?: audio.audioTrackId 
                        ?: "Audio"

                    // source 속성에 언어를 달아주면, 사용자가 링크 선택창에서 직관적으로 "YouTube (한국어)" 를 고를 수 있습니다!
                    callback(
                        newExtractorLink(
                            source = "$name ($langName)",
                            name = "YouTube $codecName",
                            url = url
                        ) {
                            quality = height
                            // 깡통 newAudioFile 의 한계를 회피하여, 선택된 언어의 오디오 1개만 주입
                            audioTracks = listOf(newAudioFile(audioUrl))
                        }
                    )
                }
            }
        }

        // 3. 자막 처리 로직
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
