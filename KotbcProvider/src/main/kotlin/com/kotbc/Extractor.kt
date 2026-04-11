package com.kotbc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.Collections
import kotlin.coroutines.resume

/**
 * KotbcExtractor v4.0
 * - [v4.0] 동적 mainUrl 동기화: 하드코딩된 Referer 대신 Kotbc 클래스에서 자동 갱신된 currentMainUrl을 안전하게 사용
 * - [v3.5] security error(도용 방지) 우회를 위해 WebView의 실제 Request Header를 통째로 추출하여 재사용
 */
class KotbcExtractor : ExtractorApi() {
    override val name = "KOTBC"
    override val mainUrl = "https://mov.glamov.com"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    data class InterceptResult(val url: String, val headers: Map<String, String>)

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[Kotbc v4.0] getUrl 실행: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // [v4.0] referer가 null이거나 구버전 도메인으로 넘어왔을 경우 최신 도메인으로 강제 교체
        val safeReferer = if (referer.isNullOrEmpty() || referer.contains("m136")) {
            Kotbc.currentMainUrl + "/"
        } else {
            referer
        }
        
        println("[Kotbc v4.0] 최종 참조용 Referer: $safeReferer")
        val hookResult = runWebViewHook(url, safeReferer)
        
        if (hookResult != null) {
            var finalUrl = hookResult.url
            println("[Kotbc v4.0] WebView 캡처 성공: $finalUrl")
            
            val headers = hookResult.headers.toMutableMap()
            
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = DESKTOP_UA
            }

            val cookie = CookieManager.getInstance().getCookie(finalUrl)
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
                println("[Kotbc v4.0] 쿠키 셋팅 완료: $cookie")
            }

            println("[Kotbc v4.0] 최종적으로 사용할 위장 헤더: $headers")

            try {
                println("[Kotbc v4.0] 캡처된 URL 본문 다운로드 및 파싱 시도: $finalUrl")
                val response = app.get(finalUrl, headers = headers)
                
                val content = try {
                    response.text.trim()
                } catch (e: Exception) {
                    println("[Kotbc v4.0] text 파싱 에러 (textLarge 시도): ${e.message}")
                    response.document.text().trim() 
                }

                println("[Kotbc v4.0] ================= 본문 내용 시작 =================")
                println(content.take(2000))
                if (content.length > 2000) println("... (이하 생략됨, 총 길이: ${content.length}) ...")
                println("[Kotbc v4.0] ================= 본문 내용 끝 ===================")

                if (content.contains("security error")) {
                    println("[Kotbc v4.0] ❌ 여전히 보안 에러(security error) 발생. 서버가 다른 토큰을 요구할 수 있습니다.")
                } else if (!content.startsWith("#EXTM3U")) {
                    println("[Kotbc v4.0] 본문이 M3U8 포맷이 아님. 내부 M3U8 링크 추출 탐색")
                    val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                    m3u8Regex.find(content)?.let {
                        finalUrl = it.groupValues[1]
                        println("[Kotbc v4.0] 실제 M3U8 주소 추출 성공: $finalUrl")
                    } ?: run {
                        println("[Kotbc v4.0] 본문에서 M3U8 링크를 찾지 못함. 원본 URL 유지.")
                    }
                } else {
                    println("[Kotbc v4.0] 캡처된 URL 본문이 정상적인 #EXTM3U 포맷입니다.")
                }
            } catch (e: Exception) {
                println("[Kotbc v4.0] 파싱 중 치명적 예외 발생: ${e.message}")
            }

            callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                this.headers = headers
            })
            println("[Kotbc v4.0] 최종 ExtractorLink 전달 완료: $finalUrl")
            return true
        } else {
            println("[Kotbc v4.0] WebView 캡처 실패")
            return false
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<InterceptResult?> { cont ->
        println("[Kotbc v4.0] WebView 훅 실행: $url")
        val handler = Handler(Looper.getMainLooper())
        
        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        println("[Kotbc v4.0] WebView Timeout")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        val isSegment = Regex("""_[0-9]+\.(html|ts)(\?.*)?$""").containsMatchIn(reqUrl)
                        if (isSegment) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        if ((reqUrl.contains(".m3u8") || reqUrl.contains(".html") || reqUrl.contains("master") || reqUrl.contains(".txt")) 
                            && (Regex("p[1-9][0-9]?player2\\.xyz").containsMatchIn(reqUrl)  || reqUrl.contains("bunny-frame") || reqUrl.contains("glamov") || reqUrl.contains("nnmo0oi1.com"))) {
                            
                            println("[Kotbc v4.0] Target URL Intercepted: $reqUrl")
                            
                            handler.removeCallbacks(discoveryTimeout)
                            
                            if (cont.isActive) {
                                val requestHeaders = request?.requestHeaders ?: emptyMap()
                                println("[Kotbc v4.0] 브라우저 원본 요청 헤더 캡처 성공")
                                
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                                cont.resume(InterceptResult(reqUrl, requestHeaders))
                            }
                            return null
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to referer))

            } catch (e: Exception) {
                println("[Kotbc v4.0] WebView Init Error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
