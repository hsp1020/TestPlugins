package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
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
            // 본진 코어 앱과 100% 동일하게 StreamInfo 1회 호출 (403, 2004 에러 원천 차단)
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

        // 2. 비디오 링크 단 1개에 모든 언어의 오디오를 쓸어 담습니다 (링크 과부하 방지)
        videoStreams.forEach { video ->
            val vUrl = video.content ?: return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube ${normalizeCodec(video.codec)}",
                    url = vUrl
                ) {
                    quality = video.height ?: 0
                    
                    // 🔥 [혁신적인 해결책] 코어의 newAudioFile() 깡통 함수를 우회하여,
                    // AudioFile 객체를 직접 생성하면서 언어 이름(langName)을 완벽하게 주입합니다!
                    audioTracks = audioStreams.mapNotNull { audio ->
                        val aUrl = audio.content ?: return@mapNotNull null
                        
                        // 한국어 번역 우선, 없으면 유튜브 원본 디스플레이 이름 사용
                        val langName = audio.audioLocale?.getDisplayLanguage(Locale.KOREAN) 
                            ?: audio.audioLocale?.displayName 
                            ?: audio.audioTrackId 
                            ?: "Audio"
                            
                        // 빌드 에러를 막고 und 버그를 잡는 핵심 객체 생성
                        com.lagradost.cloudstream3.AudioFile(aUrl, langName)
                    }
                }
            )
        }

        // 3. 자막 추출 (다국어 유지)
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
