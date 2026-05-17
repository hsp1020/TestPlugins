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

    // 1080p 화질 토큰(그리드 우회)을 얻기 위한 SOOP 공식 안드로이드 앱 위장용 User-Agent
    private val appUserAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918N Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/114.0.5735.196 Mobile Safari/537.36 SOOP/7.9.13"
    
    // 정상적인 비디오 도메인을 얻어내기 위한 일반 PC 위장용 User-Agent
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
            
            // BJ ID와 방송 번호(BNO)를 파이프(|) 기호 대신 안전하게 경로로 묶어 넘깁니다.
            val url = "$mainUrl/$bjId/$bno"
            val poster = "https://liveimg.sooplive.co.kr/m/${bno}"

            newLiveSearchResponse(title, url, TvType.Live) {
                this.posterUrl = poster
            }
        } ?: emptyList()

        // 💡 썸네일을 세로가 아닌 가로형(16:9)으로 깔끔하게 출력하도록 강제 설정
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
        
        // URL에서 방송 번호(bno)와 스트리머 ID(bjId) 추출
        val bno = if (parts.size >= 5 && parts[4].matches(Regex("\\d+"))) parts[4] else ""
        val bjId = if (bno.isNotEmpty()) parts[3] else parts.last()
        
        return newLiveStreamLoadResponse(
            "SOOP Live", 
            url,         
            "$bjId|$bno" // loadLinks()에서 받아서 쓸 핵심 데이터
        ) {
            if (bno.isNotEmpty()) {
                this.posterUrl = "https://liveimg.sooplive.co.kr/m/$bno"
            }
        }
    }

    /**
     * 스트리밍 주소(m3u8) 획득 및 재생 (그리드 1080p 우회 핵심 로직)
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

        val playerApiUrl = "https://live.sooplive.co.kr/afreeca/player_live_api.php"

        // [STEP 1] 영상 CDN 도메인 획득 (채팅 도메인 낚시를 피하기 위해 PC 크롬으로 위장)
        val playerForm = mutableMapOf(
            "bid" to bjId,
            "type" to "live",
            "player_type" to "html5",
            "stream_type" to "common",
            "quality" to "original"
        )
        if (bno.isNotEmpty()) playerForm["bno"] = bno
        
        val playerResText = app.post(
            playerApiUrl, 
            data = playerForm, 
            headers = mapOf(
                "User-Agent" to pcUserAgent,
                "Referer" to "https://play.sooplive.co.kr/$bjId/"
            )
        ).text
        
        val cleanJson = playerResText.replace("\\/", "/")
        
        if (bno.isEmpty()) {
            val bnoMatch = """"BNO"\s*:\s*"?(\d+)"?""".toRegex(RegexOption.IGNORE_CASE).find(cleanJson)
            bno = bnoMatch?.groupValues?.get(1) ?: return false
        }
        
        // 💡 핵심 해결: 함정인 chat 도메인을 완벽히 거르고 실제 영상 서버인 'cdn'이 포함된 서버만 타겟팅합니다.
        val cdnRegex = """(https?://[^"]*cdn[^"]*sooplive\.com[^"]*)""".toRegex(RegexOption.IGNORE_CASE)
        var chDomain = cdnRegex.find(cleanJson)?.groupValues?.get(1) 

        // 추출 실패 시 작동할 안전한 글로벌 CDN 백업 도메인
        if (chDomain.isNullOrEmpty()) {
            chDomain = "https://live-global-cdn-v02.sooplive.com/live-stmc-1"
        }

        // [STEP 2] 1080p 권한이 부여된 AID 화질 토큰 발급 (SOOP 모바일 앱으로 위장)
        val aidForm = mapOf(
            "bid" to bjId,
            "bno" to bno,
            "type" to "aid",
            "player_type" to "html5",
            "stream_type" to "common",
            "quality" to "original", // 1080p 해상도 강제 요청
            "cq" to "original"       // 그리드 설치 확인을 무시하도록 모바일 위장
        )
        
        val aidResText = app.post(
            playerApiUrl, 
            headers = mapOf(
                "User-Agent" to appUserAgent,
                "Origin" to "https://m.sooplive.co.kr",
                "Referer" to "https://m.sooplive.co.kr/"
            ), 
            data = aidForm
        ).text
        
        val aidMatch = """"AID"\s*:\s*"([^"]+)"""".toRegex(RegexOption.IGNORE_CASE).find(aidResText.replace("\\/", "/"))
        val aidToken = aidMatch?.groupValues?.get(1) ?: return false

        // [STEP 3] 최종 재생 주소 조립
        val m3u8Url = "$chDomain/$bno/auth_playlist.m3u8?aid=$aidToken"

        // 앱 플레이어 연동
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
