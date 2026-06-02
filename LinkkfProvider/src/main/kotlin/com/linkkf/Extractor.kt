package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper

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
            val subUrl = if (playerData.url != null) "$SUBTITLE_BASE_URL/${playerData.url}.vtt" else ""

            if (actualUrl.contains(".m3u8")) return LinkkfResult(actualUrl, subUrl, needsWebView = false)
            return LinkkfResult(actualUrl, subUrl, needsWebView = true)
        } catch (e: Exception) { return null }
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}
