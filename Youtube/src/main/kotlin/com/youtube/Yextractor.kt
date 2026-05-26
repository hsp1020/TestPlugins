package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

class YoutubeShortLinkExtractor : YoutubeExtractor() {
    override val mainUrl = "https://youtu.be"
}

class YoutubeMobileExtractor : YoutubeExtractor() {
    override val mainUrl = "https://m.youtube.com"
}

class YoutubeNoCookieExtractor : YoutubeExtractor() {
    override val mainUrl = "https://www.youtube-nocookie.com"
}

open class YoutubeExtractor : ExtractorApi() {

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

        var info: StreamInfo? = null
        var lastException: Exception? = null

        // 💡 [보완 1] 빈번한 링크 누락 현상 해결을 위해 최대 3회 자동 백그라운드 재시도 로직 탑재
        for (retry in 1..3) {
            try {
                println("[YT_DEBUG] [getUrl] 유튜브 정보 파싱 시도 ($retry/3)")
                info = StreamInfo.getInfo(watchUrl)
                if (info != null) break
            } catch (e: Exception) {
                lastException = e
                kotlinx.coroutines.delay(600) // 실패 시 0.6초 대기 후 무소음 재시도
            }
        }

        if (info == null) {
            println("[YT_DEBUG] [getUrl] 유튜브 정보 파싱 최종 실패")
            if (lastException != null) logError(lastException)
            return
        }

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

        if (videoStreams.isEmpty()) return false

        // 💡 [보완 2] 다국어 오디오(und, 영어 더빙 등)로 인한 리스트 스패밍을 해결하기 위해 오직 오리지널(원음) 트랙만 필터링합니다.
        val audioStreams = info.audioStreams.orEmpty().filter { audio ->
            audio.audioTrackType == null 
            || audio.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL
        }

        videoStreams.forEach { video ->

            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube ${normalizeCodec(video.codec)}",
                    url = video.content
                ) {
                    quality = video.height
                    audioTracks = audioStreams.map { newAudioFile(it.content) }
                }
            )
        }


        info.subtitles.forEach { subtitle ->
            subtitleCallback(
                newSubtitleFile(
                    lang = subtitle.displayLanguageName
                        ?: subtitle.languageTag
                        ?: "Unknown",
                    url = subtitle.content
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
