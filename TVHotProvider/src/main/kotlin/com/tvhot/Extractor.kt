package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*
import kotlin.random.Random

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. 서버 번호 추출
        val serverNum = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 2. 랜덤 IP 생성 (IP 차단 우회 시도)
        // 한국 대역대 혹은 랜덤 대역 IP를 생성하여 헤더에 넣음
        val randomIp = "${Random.nextInt(1, 255)}.${Random.nextInt(0, 255)}.${Random.nextInt(0, 255)}.${Random.nextInt(0, 255)}"
        
        // IP 스푸핑을 위한 헤더 맵 생성
        val spoofHeaders = mapOf(
            "X-Forwarded-For" to randomIp,
            "X-Real-IP" to randomIp,
            "Client-IP" to randomIp,
            "Referer" to (referer ?: mainUrl) // 기본 페이지 로드 시에는 referer 유지
        )

        // 3. 플레이어 페이지 로드 (스푸핑 헤더 적용)
        val response = app.get(url, headers = spoofHeaders).text
        
        // 4. 경로 탐색
        val pathRegex = Regex("""(?i)(?:/v/f/|src=)([a-f0-9]{32,})""")
        val idMatch = pathRegex.find(response)?.groupValues?.get(1)
            ?: pathRegex.find(url)?.groupValues?.get(1)

        // m3u8 요청용 Referer (전체 URL)
        val m3u8Referer = url 

        // m3u8 요청 및 재생 시 사용할 최종 헤더 (IP 스푸핑 + Referer)
        val finalHeaders = spoofHeaders.toMutableMap().apply {
            put("Referer", m3u8Referer)
        }

        if (idMatch != null) {
            println("DEBUG_EXTRACTOR: Found ID: $idMatch / Fake IP: $randomIp")
            
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            // 5. 토큰 페이지 접속 시도
            try {
                // 토큰 페이지에도 가짜 IP 헤더 전송
                val tokenResponse = app.get(tokenUrl, headers = finalHeaders).text
                val realM3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                val realM3u8 = realM3u8Regex.find(tokenResponse)?.groupValues?.get(1)
                    ?: directM3u8 
                
                invokeLink(realM3u8, finalHeaders, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, finalHeaders, callback)
            }
        } else {
            val fallbackRegex = Regex("""[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
            val fallbackMatch = fallbackRegex.find(response)?.value?.replace("\\/", "/")
            
            fallbackMatch?.let {
                val finalUrl = if (it.startsWith("http")) it else domain + it
                invokeLink(finalUrl, finalHeaders, callback)
            }
        }
    }

    private suspend fun invokeLink(m3u8Url: String, headers: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        println("DEBUG_EXTRACTOR: Final URL: $m3u8Url")
        
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                // ExtractorLink에 헤더를 직접 주입 (이게 있어야 플레이어도 가짜 IP 헤더를 씀)
                // headers는 mapOf("Referer" to ..., "X-Forwarded-For" to ...) 등을 포함함
                this.headers = headers 
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
