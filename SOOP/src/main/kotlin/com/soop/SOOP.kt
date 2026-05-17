package com.soop

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class SOOP : MainAPI() {
    override var name = "SOOP"
    override var mainUrl = "https://m.sooplive.com"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // 메인 페이지 탭 구성 (API 쿼리 파라미터와 연동)
    override val mainPage = mainPageOf(
        "view_cnt" to "시청자 많은 순"
    )

    // 모바일 앱으로 완벽 위장하기 위한 공식 앱 User-Agent
    private val appUserAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918N Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/114.0.5735.196 Mobile Safari/537.36 SOOP/7.9.13"

    // JSON 파싱용 데이터 클래스
    data class LiveListResponse(val broad_list: List<BroadItem>?)
    data class BroadItem(val broad_title: String?, val broad_no: String?, val user_id: String?)
    data class PlayerApiResponse(val CHANNEL: ChannelInfo?)
    data class ChannelInfo(val BNO: String?, val CHDOMAIN: String?)
    data class AidResponse(val aid: String?)

    /**
     * 메인 페이지 (라이브 목록 로드)
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiUrl = "https://live.sooplive.com/api/main_broad_list_api.php?selectType=action&orderType=${request.data}&pageNo=$page"
        val response = app.get(apiUrl).parsedSafe<LiveListResponse>()

        val items = response?.broad_list?.map { broad ->
            val title = broad.broad_title ?: "Unknown"
            val bjId = broad.user_id ?: ""
            val url = "$mainUrl/$bjId"
            val poster = "https://liveimg.sooplive.com/m/${broad.broad_no}"

            // 💡 최신 빌더 패턴 적용
            newLiveSearchResponse(title, url, TvType.Live) {
                this.posterUrl = poster
            }
        } ?: emptyList()

        // 💡 최신 빌더 패턴 적용
        return newHomePageResponse(request.name, items)
    }

    /**
     * 검색 기능 (라이브 방송용)
     */
    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList() // 필요 시 검색 API 연동 가능
    }

    /**
     * 상세 페이지 로드
     */
    override suspend fun load(url: String): LoadResponse {
        // url은 "$mainUrl/$bjId" 형태로 넘어옵니다.
        val bjId = url.substringAfterLast("/")
        
        // 💡 최신 빌더 패턴 적용
        return newLiveStreamLoadResponse(
            "SOOP Live", // name
            url,         // url
            bjId         // dataUrl (loadLinks로 전달될 값)
        )
    }

    /**
     * 스트리밍 주소(m3u8) 및 화질 토큰 획득 (그리드 우회 핵심)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bjId = data

        // 1. 플레이어 API에서 방송 고유 번호(bno)와 CDN 도메인 획득
        val playerApiUrl = "https://live.sooplive.com/afreeca/player_live_api.php"
        val playerForm = mapOf("bid" to bjId, "type" to "live")
        val playerRes = app.post(
            playerApiUrl, 
            data = playerForm, 
            headers = mapOf("User-Agent" to appUserAgent)
        ).parsedSafe<PlayerApiResponse>()
        
        val bno = playerRes?.CHANNEL?.BNO ?: return false
        var chDomain = playerRes.CHANNEL.CHDOMAIN ?: "https://live-m-kr-cdn.sooplive.com/live-stmc-1"
        
        // 모바일 CDN으로 변경하여 PC 그리드 우회 강제
        chDomain = chDomain.replace("live-pcweb", "live-m")

        // 2. 1080p 원본 화질을 위한 토큰(aid) 요청
        val aidUrl = "https://live-api.sooplive.com/live-api/api/aid"
        val aidHeaders = mapOf(
            "User-Agent" to appUserAgent,
            "Origin" to "https://m.sooplive.com",
            "Referer" to "https://m.sooplive.com/"
        )
        val aidForm = mapOf(
            "bno" to bno,
            "stream_type" to "common",
            "type" to "aid",
            "quality" to "original", // 1080p 해상도 요청
            "cq" to "original"       // 그리드 검사 우회
        )
        
        val aidRes = app.post(aidUrl, headers = aidHeaders, data = aidForm).parsedSafe<AidResponse>()
        val aidToken = aidRes?.aid ?: return false

        // 최종 m3u8 주소 조립
        val m3u8Url = "$chDomain/$bno/auth_playlist.m3u8?aid=$aidToken"

        // 3. 콜백에 넘겨주기 (💡 최신 DSL 빌더 패턴 적용)
        callback.invoke(
            newExtractorLink(
                this.name,
                "1080p (No Grid)",
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://m.sooplive.com/"
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
