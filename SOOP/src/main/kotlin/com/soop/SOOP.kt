package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SoopProvider : MainAPI() {
    override var name = "SOOP"
    override var mainUrl = "https://m.sooplive.com"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // 💡 핵심 1. PC/웹브라우저가 아닌 SOOP 안드로이드 '공식 앱' User-Agent로 완벽 위장
    private val appUserAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918N Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/114.0.5735.196 Mobile Safari/537.36 SOOP/7.9.13"

    // JSON 파싱을 위한 데이터 클래스 선언
    data class LiveListResponse(val broad_list: List<BroadItem>?)
    data class BroadItem(val broad_title: String?, val broad_no: String?, val user_id: String?, val total_view_cnt: String?)
    data class PlayerApiResponse(val CHANNEL: ChannelInfo?)
    data class ChannelInfo(val BNO: String?, val CHDOMAIN: String?)
    data class AidResponse(val aid: String?)

    /**
     * 메인 화면 구성 (라이브 리스트 불러오기)
     */
    override suspend fun getMainPage(page: Int, requestFilter: ProviderOptions?): HomePageResponse {
        // 제공해주신 HTML의 내용 대신, 실제 데이터가 담긴 SOOP 모바일 API를 직접 호출합니다.
        val apiUrl = "https://live.sooplive.com/api/main_broad_list_api.php?selectType=action&orderType=view_cnt&pageNo=$page"
        val response = app.get(apiUrl).parsedSafe<LiveListResponse>()

        val items = response?.broad_list?.map { broad ->
            LiveSearchResponse(
                name = broad.broad_title ?: "Unknown",
                url = broad.user_id ?: "", // 방송 클릭 시 load() 함수로 BJ ID를 넘김
                apiName = this.name,
                type = TvType.Live,
                posterUrl = "https://liveimg.sooplive.com/m/${broad.broad_no}", // HTML에서 확인한 썸네일 규칙
            )
        } ?: emptyList()

        return HomePageResponse(listOf(HomePageList("현재 라이브 방송", items)), hasNext = true)
    }

    /**
     * 상세 페이지 진입 (BJ ID 수신)
     */
    override suspend fun load(url: String): LoadResponse {
        val bjId = url
        return LiveStreamLoadResponse(
            name = "SOOP Live",
            url = bjId,
            apiName = this.name,
            dataUrl = bjId // loadLinks() 함수로 BJ ID를 전달
        )
    }

    /**
     * 💡 핵심 2. 실제 방송 주소(1080p m3u8)를 획득하고 그리드를 우회하는 메인 로직
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bjId = data

        // [STEP 1] BJ ID를 통해 방송 번호(BNO)와 CDN 도메인을 알아냅니다.
        val playerApiUrl = "https://live.sooplive.com/afreeca/player_live_api.php"
        val playerForm = mapOf("bid" to bjId, "type" to "live")
        val playerRes = app.post(
            playerApiUrl, 
            data = playerForm, 
            headers = mapOf("User-Agent" to appUserAgent)
        ).parsedSafe<PlayerApiResponse>()
        
        val bno = playerRes?.CHANNEL?.BNO ?: return false
        var chDomain = playerRes.CHANNEL.CHDOMAIN ?: "https://live-m-kr-cdn.sooplive.com/live-stmc-1"
        
        // PC 웹용 CDN 배정 시 모바일 CDN으로 강제 치환 (그리드 방지 목적)
        chDomain = chDomain.replace("live-pcweb", "live-m")

        // [STEP 2] 화질 토큰(AID) 요청 (가장 중요한 부분)
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
            "quality" to "original", // 💡 최고 화질(1080p) 요청 파라미터
            "cq" to "original"       // 💡 그리드 검사 없이 모바일로 처리되도록 강제
        )
        
        val aidRes = app.post(aidUrl, headers = aidHeaders, data = aidForm).parsedSafe<AidResponse>()
        val aidToken = aidRes?.aid ?: return false

        // [STEP 3] 최종 1080p m3u8 원본 주소 조립
        val m3u8Url = "$chDomain/$bno/auth_playlist.m3u8?aid=$aidToken"

        // Cloudstream 내장 플레이어로 전송
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "1080p (No Grid)",
                url = m3u8Url,
                referer = "https://m.sooplive.com/",
                quality = Qualities.P1080.value,
                isM3u8 = true
            )
        )
        return true
    }
}
