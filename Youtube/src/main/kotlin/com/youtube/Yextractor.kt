package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newAudioFile
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
            // 본진 코어 앱과 동일하게 StreamInfo 1회만 호출하여 403, 2004 에러 원천 차단
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

        // 1. 영상+오디오 통합본 (Muxed) 추출
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

        // 2. 💡 [최종 묘책] 각 오디오(더빙)별로 독립된 순정 ExtractorLink를 콜백으로 보냅니다.
        videoStreams.forEach { video ->
            val vUrl = video.content ?: return@forEach
            val height = video.height ?: 0
            val codecName = normalizeCodec(video.codec)

            if (audioStreams.isEmpty()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "YouTube $codecName ${height}p",
                        url = vUrl
                    ) {
                        quality = height
                    }
                )
            } else {
                // 수많은 오디오(언어)별로 비디오와 1:1 결합하여 서버 리스트를 분할합니다.
                audioStreams.forEach { audio ->
                    val aUrl = audio.content ?: return@forEach
                    
                    // 언어 정보 가져오기 (한국어 번역 우선)
                    val displayName = audio.audioLocale?.getDisplayLanguage(Locale.KOREAN) 
                        ?: audio.audioLocale?.displayName 
                        ?: audio.audioTrackId 
                        ?: "Audio"

                    // source 속성에 다국어 이름을 주입하여 서버 선택창에서 바로 한국어/영어를 골라 재생하도록 합니다.
                    callback(
                        newExtractorLink(
                            source = "$name ($displayName)", 
                            name = "YouTube $codecName ${height}p",
                            url = vUrl
                        ) {
                            quality = height
                            // 본진의 깡통 매핑(newAudioFile) 문법 규칙을 준수하여, 빌드 에러 없이 1개 오디오만 깔끔하게 주입합니다.
                            audioTracks = listOf(newAudioFile(aUrl))
                        }
                    )
                }
            }
        }

        // 3. 자막 처리 (컴파일 에러 우회를 위해 위치 기반 인자 사용)
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
