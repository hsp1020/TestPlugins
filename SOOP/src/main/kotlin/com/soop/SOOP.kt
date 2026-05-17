package com.soop

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class SOOP : MainAPI() {
    override var name = "SOOP"
    override var mainUrl = "https://m.sooplive.co.kr"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // 메인 페이지 탭 구성
    override val mainPage = mainPageOf(
        "view_cnt" to "시청자 많은 순"
    )

    // 모바일 앱(그리드 우회)으로 완벽 위장하기 위한 공식 앱 User-Agent
    private val appUserAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918N Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/114.0.5735.196 Mobile Safari/537.36 SOOP/7.9.13"

    data class LiveListResponse(val broad: List<BroadItem>?)
    data class BroadItem(val broad_title: String?, val broad_no: String?, val user_id: String?)

    /**
     * 메인 페이지 (라이브 목록 로드)
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiUrl = "https://live.sooplive.co.kr/api/main_broad_list_api.php?selectType=action&selectValue=all&orderType=${request.data}&pageNo=$page&lang=ko_KR"
        val response = app.get(apiUrl, headers = mapOf("User-Agent" to appUserAgent)).parsedSafe<LiveListResponse>()

        val items = response?.broad?.map { broadItem ->
            val title = broadItem.broad_title ?: "Unknown"
            val bjId = broadItem.user_id ?: ""
            val bno = broadItem.broad_no ?: ""
            
            val url = "$mainUrl/$bjId/$bno"
            val poster = "https://liveimg.sooplive.co.kr/m/${bno}"

            newLiveSearchResponse(title, url, TvType.Live) {
                this.posterUrl = poster
            }
        } ?: emptyList()

        // 썸네일을 가로형으로 정렬
        val homePageList = HomePageList(
            name = request.name,
            list = items,
            isHorizontalImages = true 
        )
        return newHomePageResponse(listOf(homePageList), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    /**
     * 상세 페이지 로드
     */
    override suspend fun load(url: String): LoadResponse {
        val parts = url.split("/")
        
        val bno = if (parts.size >= 5 && parts[4].matches(Regex("\\d+"))) parts[4] else ""
        val bjId = if (bno.isNotEmpty()) parts[3] else parts.last()
        
        return newLiveStreamLoadResponse(
            "SOOP Live", 
            url,         
            "$bjId|$bno"
        ) {
            if (bno.isNotEmpty()) {
                this.posterUrl = "https://liveimg.sooplive.co.kr/m/$bno"
            }
        }
    }

    /**
     * 스트리밍 주소(m3u8) 획득 및 재생
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val bjId = parts[0]
        var bno = parts.getOrNull(1) ?: ""

        val headers = mapOf(
            "User-Agent" to appUserAgent,
            "Origin" to "https://m.sooplive.co.kr",
            "Referer" to "https://m.sooplive.co.kr/"
        )

        val playerApiUrl = "https://live.sooplive.co.kr/afreeca/player_live_api.php"

        // [STEP 1] 방송 정보(bno, CHDOMAIN) 획득
        val playerForm = mutableMapOf(
            "bid" to bjId,
            "type" to "live",
            "mode" to "landing",
            "player_type" to "html5"
        )
        if (bno.isNotEmpty()) {
            playerForm["bno"] = bno
        }
        
        val playerResText = app.post(playerApiUrl, data = playerForm, headers = headers).text
        
        val bnoMatch = """"BNO"\s*:\s*"?(\d+)"?""".toRegex(RegexOption.IGNORE_CASE).find(playerResText)
        if (bno.isEmpty()) {
            bno = bnoMatch?.groupValues?.get(1) ?: return false
        }
        
        val chDomainMatch = """"CHDOMAIN"\s*:\s*"([^"]+)"""".toRegex(RegexOption.IGNORE_CASE).find(playerResText)
        var chDomain = chDomainMatch?.groupValues?.get(1)?.replace("\\/", "/") 
            ?: "https://live-m-kr-cdn.sooplive.co.kr/live-stmc-1"
            
        // 💡 에러 원인 해결: 도메인에 https:// 가 누락된 경우 강제로 붙여줌 (ExoPlayer Invalid_URL 에러 방지)
        if (!chDomain.startsWith("http")) {
            chDomain = "https://$chDomain"
        }

        // PC 웹용 CDN을 모바일용으로 치환 (그리드 우회)
        chDomain = chDomain.replace("live-pcweb", "live-m")

        // [STEP 2] 1080p 권한 AID 토큰 발급
        val aidForm = mapOf(
            "bid" to bjId,
            "bno" to bno,
            "mode" to "landing",
            "player_type" to "html5",
            "stream_type" to "common",
            "quality" to "original", // 1080p 
            "cq" to "original",      
            "type" to "aid"          
        )
        
        val aidResText = app.post(playerApiUrl, headers = headers, data = aidForm).text
        val aidMatch = """"AID"\s*:\s*"([^"]+)"""".toRegex(RegexOption.IGNORE_CASE).find(aidResText)
        val aidToken = aidMatch?.groupValues?.get(1) ?: return false

        // [STEP 3] 최종 재생 주소 조립
        val m3u8Url = "$chDomain/$bno/auth_playlist.m3u8?aid=$aidToken"

        callback.invoke(
            newExtractorLink(
                this.name,
                "1080p (No Grid)",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://m.sooplive.co.kr/"
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
