package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import org.jsoup.Jsoup
import java.nio.charset.StandardCharsets

class LinkkfExtractor {
    private val SUBTITLE_BASE_URL = "https://k1.sub1.top/s"

    data class LinkkfResult(val m3u8Url: String, val subtitleUrl: String, val needsWebView: Boolean = false)

    suspend fun extract(url: String, referer: String): LinkkfResult? {
        try {
            val doc = app.get(url, headers = mapOf("Referer" to referer)).document
            val scriptContent = doc.select("script").find { it.data().contains("var player_") && it.data().contains("actual_url") }?.data() ?: return null

            val startIndex = scriptContent.indexOf("{")
            val endIndex = scriptContent.lastIndexOf("}")
            if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) return null

            val jsonString = scriptContent.substring(startIndex, endIndex + 1)

            val playerData = mapper.readValue(jsonString, object : TypeReference<PlayerData>() {})
            
            val actualUrl = playerData.actualUrl ?: return null
            val rawSubUrl = if (playerData.url != null) "$SUBTITLE_BASE_URL/${playerData.url}.vtt" else ""

            // [초고속 가공 로직] 리소스 점유율을 제로에 가깝게 최적화하여 자막을 정제합니다.
            val finalSubtitleUrl = if (rawSubUrl.isNotEmpty()) {
                try {
                    val rawVtt = app.get(rawSubUrl).text
                    val sanitizedVtt = sanitizeWebVtt(rawVtt, rawSubUrl)
                    
                    // 가공이 필요 없어 원본 URL이 그대로 반환된 경우, 불필요한 Base64 인코딩을 생략하고 즉시 통과
                    if (sanitizedVtt == rawSubUrl) {
                        rawSubUrl
                    } else {
                        val base64Vtt = android.util.Base64.encodeToString(
                            sanitizedVtt.toByteArray(StandardCharsets.UTF_8),
                            android.util.Base64.NO_WRAP
                        )
                        "data:text/vtt;charset=utf-8;base64,$base64Vtt"
                    }
                } catch (e: Exception) {
                    rawSubUrl
                }
            } else {
                ""
            }

            if (actualUrl.contains(".m3u8")) return LinkkfResult(actualUrl, finalSubtitleUrl, needsWebView = false)
            return LinkkfResult(actualUrl, finalSubtitleUrl, needsWebView = true)
        } catch (e: Exception) { return null }
    }

    /**
     * 무거운 메모리 할당을 최소화하고 CPU 연산을 극도로 절약하는 고속 자막 필터링 엔진입니다.
     */
    private fun sanitizeWebVtt(rawVtt: String, fallbackUrl: String): String {
        // [1단계 초고속 패스] 루비 문자나 화자 태그가 전체 자막에 단 하나도 없다면, 즉시 가공을 종료하고 원본 URL 반환 (연산량 0)
        if (!rawVtt.contains("ruby", ignoreCase = true) && 
            !rawVtt.contains("rt", ignoreCase = true) && 
            !rawVtt.contains("<v", ignoreCase = true)) {
            return fallbackUrl
        }

        return rawVtt.lineSequence().map { line ->
            // [2단계 라인 패스] 꺾쇠 괄호가 없거나, 핵심 타겟 태그가 없는 대다수의 라인(타임스탬프, 일반 텍스트 등 98%의 라인)은 가공 없이 즉시 통과
            if (!line.contains("<") || 
                (!line.contains("ruby", ignoreCase = true) && 
                 !line.contains("rt", ignoreCase = true) && 
                 !line.contains("<v", ignoreCase = true))) {
                return@map line
            }

            try {
                var processedLine = line

                // 1. 화자 태그 처리 (정규식 기반 고속 스트링 치환 - DOM 객체를 만들지 않아 매우 가볍습니다)
                if (processedLine.contains("<v", ignoreCase = true)) {
                    processedLine = processedLine.replace(Regex("<v\\s+([^>]+)>(.*?)(</v>)?"), "[$1] $2")
                }

                // 2. 다국어 태그 제거 (정규식 고속 치환)
                if (processedLine.contains("lang", ignoreCase = true)) {
                    processedLine = processedLine.replace(Regex("</?lang[^>]*>"), "")
                }

                // 3. 루비 태그가 포함된 라인(에피소드 전체 중 극소수)에 한해서만 Jsoup 파서를 극히 제한적으로 호출
                if (processedLine.contains("ruby", ignoreCase = true) || processedLine.contains("rt", ignoreCase = true)) {
                    val doc = Jsoup.parseBodyFragment(processedLine)
                    
                    // 표준 루비 태그 변환
                    doc.select("ruby").forEach { ruby ->
                        val baseText = ruby.ownText()
                        val rtText = ruby.select("rt").text()
                        if (baseText.isNotEmpty() && rtText.isNotEmpty()) {
                            ruby.replaceWith(org.jsoup.nodes.TextNode("$baseText($rtText)"))
                        } else if (rtText.isNotEmpty()) {
                            ruby.replaceWith(org.jsoup.nodes.TextNode(rtText))
                        }
                    }

                    // 독립형 rt 태그 보정
                    doc.select("rt").forEach { rt ->
                        if (rt.parent()?.normalName() != "ruby") {
                            val rtText = rt.text()
                            if (rtText.isNotEmpty()) {
                                rt.replaceWith(org.jsoup.nodes.TextNode("($rtText)"))
                            } else {
                                rt.remove()
                            }
                        }
                    }

                    // 미지원 태그는 ExoPlayer가 자체적으로 무시하므로, 무거운 Jsoup.clean()을 호출하지 않고 경량화된 html 텍스트만 추출
                    processedLine = doc.body().html()
                }

                processedLine
            } catch (e: Exception) {
                line
            }
        }.joinToString("\n")
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}
