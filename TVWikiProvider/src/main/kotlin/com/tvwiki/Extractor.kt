package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import java.net.URI

// [v121] Extractor.kt: M3U8 수동 변조 (Key 직접 다운로드 & Embedding) - 쿠키 없는 환경 돌파
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVWiki v121] [Bunny] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v121] [Bunny] extract 시작: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki v121] [성공] 재탐색으로 URL 획득: $cleanUrl")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 1. 초기 URL 설정
        var targetUrl = cleanUrl
        
        // c.html이 아니라면 (리다이렉트가 필요하다면)
        if (!targetUrl.contains("/c.html")) {
             try {
                val response = app.get(targetUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to cleanReferer))
                if(response.url.contains("/c.html")) {
                    targetUrl = response.url
                    println("[TVWiki v121] 리다이렉트된 URL: $targetUrl")
                }
             } catch(e: Exception) {
                 e.printStackTrace()
             }
        }

        try {
            // 2. M3U8 콘텐츠 다운로드 (Video 전용 헤더 사용: Main Referer)
            // v111에서 Video 로딩은 성공했으므로 이 헤더를 씀
            val videoHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Accept" to "*/*"
            )
            
            println("[TVWiki v121] M3U8 다운로드 시도: $targetUrl")
            val m3u8Response = app.get(targetUrl, headers = videoHeaders)
            var m3u8Content = m3u8Response.text
            
            // 3. Key URI 찾기 및 변조
            val keyRegex = Regex("""#EXT-X-KEY:METHOD=([^,]+),URI="([^"]+)"""")
            
            // Key를 찾아서 직접 다운로드 후 Base64로 교체
            m3u8Content = keyRegex.replace(m3u8Content) { match ->
                val method = match.groupValues[1]
                val keyUriRelative = match.groupValues[2]
                val keyUriAbsolute = URI.create(targetUrl).resolve(keyUriRelative).toString()
                
                println("[TVWiki v121] Key 발견: $keyUriAbsolute")
                
                try {
                    // 4. Key 다운로드 (Key 전용 헤더 사용: Token Referer)
                    // v108에서 Key 로딩은 성공했으므로 Token URL(c.html)을 Referer로 씀
                    val keyHeaders = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to targetUrl, // Token이 포함된 c.html 주소
                        "Accept" to "*/*"
                    )
                    
                    val keyBytes = app.get(keyUriAbsolute, headers = keyHeaders).body.bytes()
                    val base64Key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                    
                    println("[TVWiki v121] Key 다운로드 및 인코딩 성공")
                    
                    // Data URI로 교체
                    """#EXT-X-KEY:METHOD=$method,URI="data:text/plain;base64,$base64Key""""
                } catch (e: Exception) {
                    println("[TVWiki v121] Key 다운로드 실패: ${e.message}")
                    match.value // 실패 시 원본 유지 (어차피 재생 안되겠지만)
                }
            }
            
            // 5. 세그먼트 URL 절대경로화
            // 라인별로 보면서 #으로 시작하지 않는 라인(세그먼트)을 절대경로로 변경
            val baseUrl = targetUrl.substringBeforeLast("/") + "/"
            val lines = m3u8Content.lines().map { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    if (line.startsWith("http")) line else baseUrl + line
                } else {
                    line
                }
            }
            val finalM3u8 = lines.joinToString("\n")
            
            // 6. 전체 M3U8을 Data URI로 변환하여 재생
            val finalDataUri = "data:application/x-mpegURL;base64," + Base64.encodeToString(finalM3u8.toByteArray(), Base64.NO_WRAP)
            
            println("[TVWiki v121] 최종 Data URI 생성 완료")
            
            callback(
                newExtractorLink(name, name, finalDataUri, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    // Data URI를 쓰므로 헤더는 크게 의미 없지만 형식상 넣어둠
                    this.headers = videoHeaders 
                }
            )
            return true

        } catch (e: Exception) {
            println("[TVWiki v121] 처리 중 에러: ${e.message}")
            e.printStackTrace()
        }
        
        println("[TVWiki v121] 최종 실패")
        return false
    }
}
