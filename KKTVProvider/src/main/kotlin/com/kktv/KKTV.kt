package com.kktv

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://ifadetv.com"
    private val liveFrameUrl = "https://fkwdsvop.com/KingKong/livescore/livescore/"
    
    override var name = "KKTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "live_matches" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private suspend fun sniffApiUrlViaWebView(url: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (e: Exception) { null }

        if (context == null) {
            println("[KKTV API FINDER] 안드로이드 Context 획득 실패.")
            return@withContext
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = userAgent

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    
                    if (reqUrl.contains(".json") || reqUrl.contains("api") || reqUrl.contains("ajax") || reqUrl.contains("list") || reqUrl.contains("data")) {
                        println("[KKTV API FINDER] 🚨 유력 API/JSON 발견!!! -> $reqUrl")
                    } else {
                        println("[KKTV API FINDER] 일반 네트워크 요청 -> $reqUrl")
                    }
                    
                    return super.shouldInterceptRequest(view, request)
                }
            }
            
            println("[KKTV API FINDER] 백그라운드 웹뷰 추적 시작: $url")
            webView.loadUrl(url)

            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    println("[KKTV API FINDER] 10초 대기 종료. 추적 완료.")
                    continuation.resume(Unit)
                    try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                }
            }
            
            handler.postDelayed(timeoutRunnable, 10000)

            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        // 🚨 수정: UI가 멈추지 않도록(로딩 지연 없음) 백그라운드 비동기로 스니퍼를 몰래 던져놓습니다.
        GlobalScope.launch {
            try {
                sniffApiUrlViaWebView(liveFrameUrl)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 화면에는 대기 시간 없이 즉시(0.1초 컷) 아래 메시지가 뜹니다.
        items.add(
            newLiveSearchResponse(name = "화면이 켜졌습니다. 로그캣에서 [KKTV API FINDER]를 검색하세요!", url = mainUrl) {
                this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
            }
        )

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("스포츠 실시간 중계", url, url) {
            this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (data.contains(".m3u8")) {
                callback.invoke(newExtractorLink(name, "$name Live", data, type = ExtractorLinkType.M3U8) { 
                    this.referer = mainUrl; this.headers = mapOf("User-Agent" to userAgent) 
                })
                return true
            }

            val playerText = app.get(data, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            val matchUrl = """https?://[^\s"'<>]+chunklist\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex().find(playerText)?.value

            if (matchUrl != null) {
                callback.invoke(newExtractorLink(name, "$name Live HD", matchUrl, type = ExtractorLinkType.M3U8) { 
                    this.referer = data; this.headers = mapOf("User-Agent" to userAgent) 
                })
                return true
            }
        } catch (e: Exception) { e.printStackTrace() }
        return false
    }
}
