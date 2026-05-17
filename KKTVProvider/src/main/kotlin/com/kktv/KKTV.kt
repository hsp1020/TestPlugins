// v1.1 - DESKTOP_UA unresolved reference 수정
// v7.8 - 텔레그램 채널(t.me/s/kktvinfo) 기반 동적 도메인 파싱 추가 및 기존 포털 로직 Fallback 강등
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class KingkongTv : MainAPI() {
    // 기본값으로 설정해두지만, 실행 시 updateMainUrlIfNeeded()에 의해 동적으로 변경됩니다.
    override var mainUrl = "https://kktv247.com"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    
    // 고정된 안내 포털 주소
    private val portalUrl = "https://kingkongtv.org"
    // 텔레그램 안내 채널 주소 (웹 프리뷰용 /s/ 추가)
    private val telegramUrl = "https://t.me/s/kktvinfo"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    // [수정 포인트 v7.8] 텔레그램 채널 우선 파싱 -> 실패 시 포털 사이트 파싱(Fallback)
    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v7.8: 텔레그램 채널($telegramUrl)에서 최신 접속 주소 확인 시도")
            val tgDoc = app.get(telegramUrl).document
            val tgHtml = tgDoc.html()
            
            // 정규식을 사용하여 "킹콩티비 주소 : <a href="주소">" 패턴 추출 (띄어쓰기 변수 고려)
            val regex = Regex("""킹콩티비\s*주소\s*[:：]?\s*<a[^>]*href=["']([^"']+)["']""")
            val matchResult = regex.find(tgHtml)
            
            var newUrl: String? = null
            if (matchResult != null) {
                newUrl = matchResult.groupValues[1]
            }

            // 1순위: 텔레그램에서 유효한 주소를 찾았고, 그 주소가 텔레그램 자기 자신이 아닌 경우
            if (!newUrl.isNullOrBlank() && !newUrl.contains("t.me")) {
                // http로 적혀있어도 https로 강제 변환 (보안 및 Cloudstream 호환성)
                var cleanedUrl = newUrl.trimEnd('/')
                if (cleanedUrl.startsWith("http://")) {
                    cleanedUrl = cleanedUrl.replace("http://", "https://")
                }

                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.8: 텔레그램에서 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                } else {
                    println("DEBUG [KingkongTv] v7.8: 기존 주소 유지(변경사항 없음) - $mainUrl")
                }
                return // 성공했으므로 함수 종료
            }

            println("DEBUG [KingkongTv] v7.8: 텔레그램에서 주소를 찾지 못함. 기존 포털 사이트($portalUrl) 폴백 로직 실행")

            // 2순위 (Fallback): 기존 포털 사이트 파싱 로직
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            // 포털에서 가져온 주소가 텔레그램 주소가 아닐 때만 적용 (무한루프/오류 방지)
            if (!latestUrl.isNullOrBlank() && !latestUrl.contains("t.me")) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.8: 포털(Fallback)에서 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                }
            } else {
                println("DEBUG [KingkongTv] v7.8: 포털에서도 유효한 스트리밍 주소를 찾지 못했습니다. 기본 주소를 사용합니다 - $mainUrl")
            }

        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.8: 최신 주소 확인 중 오류 발생 - ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        println("DEBUG [KKTV v14.9] 메인 페이지 로드 시작 - 대상 URL: $url")
        
        try {
            val mainDoc = app.get(url).document
            var iframeSrc = mainDoc.selectFirst("iframe[src*='wdbroad.com/broadcast']")?.attr("src")
                ?: mainDoc.selectFirst("iframe#broadcastFrame")?.attr("src")
                ?: "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"

            if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"
            println("DEBUG [KKTV v14.9] iframe 소스 추출 완료: $iframeSrc")

            val realUrl = fixUrl(url, iframeSrc)
            val contentDoc = app.get(realUrl, referer = url).document
            
            var rows = contentDoc.select("table.board_list tbody tr")
            if (rows.isEmpty()) rows = contentDoc.select("div.sports_on_list table tbody tr")

            println("DEBUG [KKTV v14.9] 방송 목록 행(row) 개수: ${rows.size}")

            val sortedRows = rows.sortedByDescending { it.selectFirst("td.subject")?.hasClass("bold") == true }
            val home = sortedRows.mapNotNull { it.toSearchResult(realUrl) }

            println("DEBUG [KKTV v14.9] 최종 추출된 방송 개수: ${home.size}")
            return newHomePageResponse(HomePageList(request.name, home, true), false)
        } catch (e: Exception) {
            println("DEBUG [KKTV v14.9] 메인 페이지 파싱 중 예외 발생: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        // v14.9 - onair/offair 이미지 소스로만 단순 명료하게 판단
        val onoffImgSrc = this.selectFirst("td.onoff img")?.attr("src") ?: ""
        if (onoffImgSrc.contains("offair")) {
            println("DEBUG [KKTV v14.9] 오프라인 방송 스킵 (offair 이미지 감지)")
            return null 
        }

        var title: String? = null
        var streamId: String? = null
        var thumbUrlRaw: String? = null

        val subjectTd = this.selectFirst("td.subject")
        val onClickAttr = subjectTd?.attr("onclick")
        
        if (onClickAttr != null) {
            title = Regex("""load_video\('[^']*',\s*'[^']*',\s*'([^']+)'""").find(onClickAttr)?.groupValues?.get(1)
            streamId = Regex("""load_video\('broadcast/(\d+)'""").find(onClickAttr)?.groupValues?.get(1)
            
            val categoryMap = mapOf(
                "soccer" to "soccer",
                "base" to "baseball",
                "baseball" to "baseball",
                "basketball" to "basketball",
                "volleyball" to "volleyball",
                "hockey" to "hockey",
                "tennis" to "tennis",
                "lol" to "lol",
                "football" to "football",
                "mma" to "mma",
                "tv" to "tv"
            )

            var extractedKey = ""
            
            val trClass = this.className()
            val classMatch = Regex("""cate_([a-zA-Z0-9]+)""").find(trClass)
            if (classMatch != null) {
                extractedKey = classMatch.groupValues[1].lowercase()
            } else {
                val oldImgSrc = this.selectFirst("td.cateimg img")?.attr("src") ?: ""
                val imgMatch = Regex("""/([^/]+)\.png""").find(oldImgSrc)
                if (imgMatch != null) {
                    extractedKey = imgMatch.groupValues[1].lowercase()
                }
            }

            val normalizedCate = categoryMap[extractedKey] ?: "tv"
            thumbUrlRaw = "/TV/kktv/img/${normalizedCate}_icon.png"
            println("DEBUG [KKTV v14.9] 카테고리 분석: 추출키($extractedKey) -> 정규화($normalizedCate) -> 경로($thumbUrlRaw)")
        } 
        
        if (title.isNullOrBlank()) {
            println("DEBUG [KKTV v14.9] onclick 속성에서 타이틀을 찾지 못하여 대체 로직 시도")
            val channelDiv = this.selectFirst("div.channel_on")
            title = channelDiv?.attr("data-title")
            streamId = channelDiv?.attr("data-stream")
            
            if (thumbUrlRaw.isNullOrBlank()) {
                thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
                println("DEBUG [KKTV v14.9] 대체 요소에서 썸네일 추출 완료: $thumbUrlRaw")
            }
        }
        
        if (title.isNullOrBlank() || streamId.isNullOrBlank()) {
            println("DEBUG [KKTV v14.9] 유효하지 않은 항목 스킵 - title: $title, streamId: $streamId")
            return null
        }
        if (this.selectFirst("td.subject")?.hasClass("bold") == true) title = "🔥 $title"

        val thumbUrl = if (thumbUrlRaw != null) {
            val fixed = fixUrl(refererUrl, thumbUrlRaw)
            "$fixed${if (fixed.contains("?")) "&" else "?"}t=${System.currentTimeMillis()}"
        } else ""

        println("DEBUG [KKTV v14.9] 최종 썸네일 URL: $thumbUrl")

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedPoster = URLEncoder.encode(thumbUrl, "UTF-8")
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8") 

        val dataStr = "kktv://${streamId}|$encodedTitle|$encodedPoster|$encodedRef"

        return newMovieSearchResponse(title, dataStr, TvType.Others) { 
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("DEBUG [KKTV v14.9] load 함수 호출됨 - URL: $url")
        val parts = url.split("|")
        if (parts.size < 4) return null

        val title = URLDecoder.decode(parts[1], "UTF-8")
        val poster = URLDecoder.decode(parts[2], "UTF-8")
        
        return newMovieLoadResponse(title, url, TvType.Others, url) { 
            this.posterUrl = poster
            this.plot = "실시간 중계" 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 4) {
            println("DEBUG [KKTV v14.9] loadLinks 데이터 분리 실패")
            return false
        }

        val rawIdPart = parts[0].substringAfterLast("/")
        val streamId = Regex("""\d+""").find(rawIdPart)?.value ?: return false
        val parentReferer = URLDecoder.decode(parts[3], "UTF-8")
        
        val playerIframeUrl = "https://wdbroad.com/broadcast/$streamId/kktv"
        println("DEBUG [KKTV v14.9] 웹뷰 스니핑 시작 - 대상 URL: $playerIframeUrl")

        val hookResult = suspendCancellableCoroutine<Pair<String, Map<String, String>>?> { cont ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null

            val cleanup = Runnable {
                handler.post {
                    try {
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.clearHistory()
                        webView?.removeAllViews()
                        webView?.destroy()
                        webView = null
                        println("DEBUG [KKTV v14.9] 웹뷰 메모리 클린업 완료 (오디오 겹침 방지)")
                    } catch (e: Exception) {
                        println("DEBUG [KKTV v14.9] 클린업 중 예외 발생: ${e.message}")
                    }
                }
            }

            val timeout = Runnable {
                if (cont.isActive) {
                    println("DEBUG [KKTV v14.9] 15초 타임아웃 발생 - m3u8 포착 실패")
                    cont.resume(null)
                }
                cleanup.run()
            }
            handler.postDelayed(timeout, 15000)

            cont.invokeOnCancellation {
                println("DEBUG [KKTV v14.9] 로딩 취소 감지, 클린업 실행")
                handler.removeCallbacks(timeout)
                cleanup.run()
            }

            handler.post {
                try {
                    val context = (AcraApplication.context ?: app) as Context
                    webView = WebView(context)
                    
                    webView?.settings?.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false 
                        userAgentString = userAgent // [Fix] DESKTOP_UA 대신 클래스 내부 정의된 userAgent 변수 참조로 변경
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    var isFound = false

                    webView?.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            
                            if (!isFound && (reqUrl.contains("wdbroadcast.com") || reqUrl.contains(".m3u8") || reqUrl.contains("chunklist"))) {
                                isFound = true
                                println("DEBUG [KKTV v14.9] 🎯 M3U8 포착: $reqUrl")
                                handler.removeCallbacks(timeout)
                                
                                if (cont.isActive) {
                                    cont.resume(Pair(reqUrl, request?.requestHeaders ?: emptyMap()))
                                }
                                
                                cleanup.run()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    val cookie = CookieManager.getInstance().getCookie(parentReferer)
                    if (!cookie.isNullOrEmpty()) {
                        CookieManager.getInstance().setCookie("wdbroad.com", cookie)
                    }

                    val wrapperHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head><meta name="referrer" content="no-referrer-when-downgrade"></head>
                        <body style="margin:0;padding:0;background:#000;">
                            <iframe src="$playerIframeUrl" style="width:100%;height:100%;border:none;" allow="autoplay; fullscreen"></iframe>
                        </body>
                        </html>
                    """.trimIndent()

                    webView?.loadDataWithBaseURL(parentReferer, wrapperHtml, "text/html", "UTF-8", null)
                    
                } catch (e: Exception) {
                    println("DEBUG [KKTV v14.9] 웹뷰 생성 중 예외 발생: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                    cleanup.run()
                }
            }
        }

        if (hookResult != null) {
            val (finalUrl, reqHeaders) = hookResult
            val headers = reqHeaders.toMutableMap()
            
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = userAgent // [Fix] DESKTOP_UA 대신 클래스 내부 정의된 userAgent 변수 참조로 변경
            }
            
            headers["Referer"] = "https://wdbroad.com/"
            headers["Origin"] = "https://wdbroad.com"

            println("DEBUG [KKTV v14.9] 스트림 추출 성공 전달")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = headers
                }
            )
            return true
        }
        return false
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}
