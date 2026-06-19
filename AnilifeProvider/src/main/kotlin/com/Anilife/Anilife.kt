package com.anilife

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import javax.net.ssl.SSLHandshakeException

class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    // 이미지 403 방지를 위한 전용 헤더 (슬래시 조정을 통한 핫링크 우회 테스트)
    private val posterHeadersMap = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "https://anilife.live" 
    )

    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/top20" to "실시간 TOP 20",
        "/vodtype/categorize/TV/1" to "TV 애니메이션",
        "/vodtype/categorize/OVA/1" to "OVA",
        "/vodtype/categorize/ONA/1" to "ONA",
        "/vodtype/categorize/SP/1" to "SP",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.contains("TOP 20") && page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val items = parseCommonList(doc)
            val hasNextPage = if (request.name.contains("TOP 20")) false else items.isNotEmpty()
            
            newHomePageResponse(request.name, items, hasNext = hasNextPage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // [진단 로그 실행] 원인 A 와 B 구분
            AnilifeDiagnostic.diagnose(url, e, commonHeaders)
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)
                
                if (poster.isEmpty()) {
                    poster = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
                }
                
                val finalHref = if (poster.isNotEmpty()) {
                    val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                    if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
                } else rawHref
                
                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = posterHeadersMap
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        return try {
            parseCommonList(app.get(url, headers = commonHeaders).document)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // [진단 로그 실행] 원인 A 와 B 구분
            AnilifeDiagnostic.diagnose(url, e, commonHeaders)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        try {
            val response = app.get(cleanUrl, headers = commonHeaders)
            val doc = response.document
            val finalUrl = response.url
            val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

            val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
            val plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
            val tags = doc.select(".genxed a").map { it.text() }

            var currentEpisodeNumber = 0
            val numRegex = Regex("[^0-9.]") 

            val elements = doc.select(".eplister > ul > li > a").reversed()
            val episodes = elements.mapNotNull { element ->
                val rawHref = fixUrl(element.attr("href"))
                val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
                val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
                
                val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
                val finalHref = if (rawHref.contains("?")) "$rawHref&ref=$encodedRef" else "$rawHref?ref=$encodedRef"
                
                val parsedNum = numText.replace(numRegex, "").toFloatOrNull()?.toInt() ?: 0

                if (currentEpisodeNumber == 0) {
                    currentEpisodeNumber = if (parsedNum > 0) parsedNum else 1
                } else {
                    if (parsedNum > currentEpisodeNumber) {
                        currentEpisodeNumber = parsedNum
                    } else {
                        currentEpisodeNumber++
                    }
                }

                newEpisode(finalHref) {
                    this.name = fullName
                    this.episode = currentEpisodeNumber
                }
            }

            return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
                this.posterUrl = doc.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
                this.posterHeaders = posterHeadersMap
                this.plot = plot
                this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // [진단 로그 실행] 원인 A 와 B 구분
            AnilifeDiagnostic.diagnose(cleanUrl, e, commonHeaders)
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { }
        }

        val videoIdMatch = Regex("""(?:id|provider)/([^/?]+)""").find(cleanData)
        val videoId = videoIdMatch?.groupValues?.get(1) ?: "unknown_id"
        val linksHeaders = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent)

        try {
            val webResponse = app.get(cleanData, headers = linksHeaders)
            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false

            return AnilifeProxyExtractor().extractWithProxy(
                m3u8Url = "",
                playerUrl = playerUrl,
                referer = "https://anilife.live/",
                ssid = null,
                cookies = "",
                targetKeyUrl = null,
                videoId = videoId,
                callback = callback
            )

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // [진단 로그 실행] 원인 A 와 B 구분
            AnilifeDiagnostic.diagnose(cleanData, e, linksHeaders)
        }
        return false
    }
}

/**
 * [Anilife 전용 네트워크 오류 정밀 분석기]
 * 동일 패키지 내에서 공유되므로 Extractor.kt에서도 import 없이 자유롭게 호출 가능합니다.
 */
object AnilifeDiagnostic {
    private const val TAG = "Anilife-Diagnostic"

    suspend fun diagnose(url: String, e: Throwable, headers: Map<String, String>) {
        try {
            Log.e(TAG, "==========================================================================")
            Log.e(TAG, "🚨 [Anilife-Diagnostic] 네트워크 오류 감지 - 원인 분석 보고서")
            Log.e(TAG, "📍 대상 URL: $url")
            Log.e(TAG, "📍 예외 종류: ${e.javaClass.name}")
            Log.e(TAG, "📍 예외 메시지: ${e.message}")
            Log.e(TAG, "==========================================================================")

            // 1. 구글 테스트 통신을 통한 기본 인터넷망 정상 작동 여부 판단
            var localInternetActive = false
            try {
                val googleTest = app.get("https://www.google.com").code
                if (googleTest == 200) {
                    localInternetActive = true
                    Log.d(TAG, "✅ [1단계: 기기 네트워크 상태]: 인터넷이 정상적으로 작동 중입니다. (Google 접속 성공)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "❌ [1단계: 기기 네트워크 상태]: 기기의 인터넷 연결 자체가 끊겼거나 DNS 응답이 없습니다. (Google 핑 실패: ${t.message})")
            }

            if (localInternetActive) {
                val exceptionMsg = e.message ?: ""
                val isSSLFailure = e is SSLHandshakeException || exceptionMsg.contains("SSL", ignoreCase = true) || exceptionMsg.contains("handshake", ignoreCase = true)
                val isAbruptClose = exceptionMsg.contains("connection closed", ignoreCase = true) || exceptionMsg.contains("reset", ignoreCase = true) || exceptionMsg.contains("shutdown", ignoreCase = true)

                Log.e(TAG, "🔍 [2단계: 차단 분석 자가진단 결과]")

                if (isSSLFailure || isAbruptClose) {
                    Log.e(TAG, "💡 [원인 A 가능성 검증]: 대한민국 인터넷 검열망(SNI) 차단 상태")
                    Log.e(TAG, "   └─ 증상: 구글은 정상 접속되나 'anilife.live' 도메인과의 HTTPS 핸드셰이크가 강제 차단되었습니다.")
                    Log.e(TAG, "   └─ 우회 여부 확인법: 기기에 우회 앱(SecretSNI, 유니콘 HTTPS, Intra 등)을 실행하거나 VPN을 켜고 다시 실행해 보세요.")
                    Log.e(TAG, "   └─ 우회 도구 작동 시 로드가 원활하게 이루어진다면 100% [원인 A]에 해당합니다.")
                    Log.e(TAG, "   ----------------------------------------------------------------------")
                    Log.e(TAG, "💡 [원인 B 가능성 검증]: Cloudflare JA3 / TLS 핑거프린트 차단 상태")
                    Log.e(TAG, "   └─ 증상: VPN 이나 SNI 우회 도구를 정상적으로 실행하고 책임 테스트를 진행했음에도 동일한 핸드셰이크 오류가 지속됩니다.")
                    Log.e(TAG, "   └─ 설명: Cloudflare 방화벽이 모바일 OkHttp 라이브러리 고유의 암호화 악수 정보(JA3)를 브라우저가 아닌 비정상 봇으로 필터링하여 끊는 상태입니다.")
                } else if (exceptionMsg.contains("403") || exceptionMsg.contains("503")) {
                    Log.e(TAG, "💡 [원인 B 검증 성공]: Cloudflare WAF 방화벽 차단 (HTTP 403/503)")
                    Log.e(TAG, "   └─ 설명: TLS 연결은 완료되었으나, 서버 보안 장비에서 비정상 헤더 혹은 사용자 차단 규칙을 감지해 엑세스를 거부했습니다.")
                } else {
                    Log.e(TAG, "💡 [기타 오류]: 단순 접속 시간 초과 또는 일시적인 대상 서버 다운 상태일 수 있습니다.")
                }
            }

            // CoilImgLoader의 이미지 403 대응 팁 기록
            Log.e(TAG, "💡 [추가 팁 - CoilImgLoader: Image load error: HTTP 403 해결방안]")
            Log.e(TAG, "   └─ 증상: 홈 화면의 카드는 생겼으나 포스터만 엑스박스(빈 이미지)로 뜬다면, 이미지 저장소 'image.anilife.life' 가 Referer 규격을 대조하여 거부 중입니다.")
            Log.e(TAG, "   └─ 조치: posterHeadersMap 에 정의된 Referer 를 수정하거나 주석 처리하여 대조를 무력화하는 실험을 하십시오.")
            Log.e(TAG, "==========================================================================")
        } catch (fatal: Exception) {
            // 자가 진단 도중 발생하는 추가 에러가 앱 로딩을 절대 간섭하지 않도록 예외 차단
        }
    }
}
