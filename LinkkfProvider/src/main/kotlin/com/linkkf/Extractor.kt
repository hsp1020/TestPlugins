package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
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

            // [고유 개선] 앱 업데이트 시 깨지는 inline 확장 함수(readValue<T>) 대신,
            // 가장 원시적이고 안전한 TypeReference 직접 주입 방식으로 변경 (ABI 충돌 원천 차단)
            val playerData = mapper.readValue(jsonString, object : TypeReference<PlayerData>() {})
            
            val actualUrl = playerData.actualUrl ?: return null
            val rawSubUrl = if (playerData.url != null) "$SUBTITLE_BASE_URL/${playerData.url}.vtt" else ""

            // [WebVTT 완전 지원 구현]
            // ExoPlayer의 한계를 극복하기 위해 원격 VTT 자막을 실시간 다운로드하여 정밀 가공 후 Base64 데이터 스트림으로 교체합니다.
            val finalSubtitleUrl = if (rawSubUrl.isNotEmpty()) {
                try {
                    val rawVtt = app.get(rawSubUrl).text
                    val sanitizedVtt = sanitizeWebVtt(rawVtt)
                    
                    val base64Vtt = android.util.Base64.encodeToString(
                        sanitizedVtt.toByteArray(StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    "data:text/vtt;charset=utf-8;base64,$base64Vtt"
                } catch (e: Exception) {
                    rawSubUrl // 자막 다운로드 혹은 가공 중 실패 시 원본 서브타이틀 URL로 안전하게 대체
                }
            } else {
                ""
            }

            if (actualUrl.contains(".m3u8")) return LinkkfResult(actualUrl, finalSubtitleUrl, needsWebView = false)
            return LinkkfResult(actualUrl, finalSubtitleUrl, needsWebView = true)
        } catch (e: Exception) { return null }
    }

    /**
     * WebVTT 규격 문서에서 ExoPlayer가 정상 처리하지 못하는 비호환 태그들을
     * 공식 지원 규격(b, i, u, c) 및 순수 텍스트 주석으로 구조화하여 정제합니다.
     */
    private fun sanitizeWebVtt(rawVtt: String): String {
        return rawVtt.lineSequence().map { line ->
            // 타임라인 지표나 순수 메타데이터처럼 태그를 미포함한 라인은 손대지 않고 고속 통과시킵니다.
            if (!line.contains("<") || !line.contains(">")) {
                return@map line
            }

            try {
                var processedLine = line

                // 1. 화자 정보(Voice) 유실 방지: <v Host>텍스트 -> [Host] 텍스트 형태로 치환하여 화면 표시 유지
                processedLine = processedLine.replace(Regex("<v\\s+([^>]+)>(.*?)(</v>)?"), "[$1] $2")

                // 2. 다국어 메타 태그 무력화: <lang ko-KR>텍스트</lang> -> 텍스트 형태로 간소화
                processedLine = processedLine.replace(Regex("</?lang[^>]*>"), "")

                // Jsoup HTML DOM 분석기를 호출하여 계층적 마크업 분석을 수행합니다.
                val doc = Jsoup.parseBodyFragment(processedLine)

                // 3. 표준 루비 태그 변환: <ruby>본문<rt>주석</rt></ruby> -> 본문(주석)
                doc.select("ruby").forEach { ruby ->
                    val baseText = ruby.ownText()       // 루비 태그 내부의 순수 텍스트 노드 추출
                    val rtText = ruby.select("rt").text() // 어깨글자 텍스트 추출
                    if (baseText.isNotEmpty() && rtText.isNotEmpty()) {
                        ruby.replaceWith(org.jsoup.nodes.TextNode("$baseText($rtText)"))
                    } else if (rtText.isNotEmpty()) {
                        ruby.replaceWith(org.jsoup.nodes.TextNode(rtText))
                    }
                }

                // 4. 비표준 독립 rt 태그 보정: <ruby> 없이 임의로 남발된 본문<rt>주석</rt> 처리 -> 본문(주석)
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

                // 5. 화이트리스트 필터링: ExoPlayer가 파싱 시 오류 없이 완벽히 지원하는 b, i, u, c 태그 이외의 모든 이상 요소 차단
                val safelist = Safelist.none().addTags("b", "i", "u", "c")
                val cleanedHtml = Jsoup.clean(doc.body().html(), safelist)

                cleanedHtml
            } catch (e: Exception) {
                line // 개별 라인 처리 예외 발생 시 원본 보호
            }
        }.joinToString("\n")
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}
