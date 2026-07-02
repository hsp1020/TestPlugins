package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper

class LinkkfExtractor {
    // 최신 자막 도메인 (기본)
    private val SUBTITLE_BASE_URL = "https://subk.xyk2.top/s"
    // 기존 자막 도메인 (Fallback)
    private val SUBTITLE_FALLBACK_BASE_URL = "https://k1.sub1.top/s"

    data class LinkkfResult(
        val m3u8Url: String, 
        val subtitleUrl: String, 
        val fallbackSubtitleUrl: String = "", 
        val needsWebView: Boolean = false
    )

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
            val urlParam = playerData.url
            
            val subUrl = if (urlParam != null) "$SUBTITLE_BASE_URL/$urlParam.vtt" else ""
            val fallbackSubUrl = if (urlParam != null) "$SUBTITLE_FALLBACK_BASE_URL/$urlParam.vtt" else ""

            if (actualUrl.contains(".m3u8")) {
                return LinkkfResult(actualUrl, subUrl, fallbackSubUrl, needsWebView = false)
            }
            return LinkkfResult(actualUrl, subUrl, fallbackSubUrl, needsWebView = true)
        } catch (e: Exception) { return null }
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}
