package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
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

        try {
            // 본진 코어 앱과 동일하게 StreamInfo 1회만 호출하여 403, 2004 에러 차단
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
        } catch (e: Exception) {
            logError(e)
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

        // 1. 영상+오디오 통합본 (Muxed) 백업 추출
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

        // 2. 단일 비디오 링크 안에 다국어 오디오 트랙 정보와 이름표를 정상적으로 주입
        videoStreams.forEach { video ->
            val vUrl = video.content ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube ${normalizeCodec(video.codec)}",
                    url = vUrl
                ) {
                    quality = video.height ?: 0
                    
                    // 💡 [질문자님 묘책 적용] newAudioFile의 두 번째 인자인 람다 블록을 열어 
                    // 내부에서 직접 name과 lang을 오디오 파일 제목으로 세팅해 줍니다!
                    audioTracks = audioStreams.mapNotNull { audio ->
                        val aUrl = audio.content ?: return@mapNotNull null
                        val langCode = audio.audioLocale?.language ?: audio.audioTrackId ?: "und"
                        val displayName = audio.audioLocale?.getDisplayLanguage(Locale.KOREAN) 
                            ?: audio.audioLocale?.displayName 
                            ?: audio.audioTrackId 
                            ?: "Audio"

                        newAudioFile(aUrl) {
                            it.name = displayName
                            it.lang = langCode
                        }
                    }
                }
            )
        }

        // 3. 자막 처리
        info.subtitles.orEmpty().forEach { subtitle ->
            val subUrl = subtitle.content ?: return@forEach
            val langName = subtitle.displayLanguageName ?: subtitle.languageTag ?: "Unknown"
            subtitleCallback(
                newSubtitleFile(langName, subUrl)
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
