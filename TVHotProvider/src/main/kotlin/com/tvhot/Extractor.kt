package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import android.util.Log // 안드로이드 로그용
import com.lagradost.cloudstream3.mvvm.logError 

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 로그를 찍는 헬퍼 함수
    private fun debugLog(msg: String) {
        // 1. ADB Logcat에 출력 (태그: TVHOT_DEBUG)
        Log.e("TVHOT_DEBUG", msg) 
        println("TVHOT_DEBUG: $msg")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        debugLog("=== 분석 시작 ===")
        debugLog("요청 URL: $url")
        debugLog("리퍼러: $referer")

        val headers = mapOf(
            "Referer" to "$referer",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        try {
            val response = app.get(url, headers = headers)
            debugLog("응답 코드: ${response.code}")

            if (response.code != 200) {
                throw Error("접속 실패 Code:${response.code}")
            }

            val responseText = response.text
            debugLog("원본 HTML 길이: ${responseText.length}")

            // Packed JS 해제
            val unpackedBody = if (responseText.contains("eval(function(p,a,c,k,e,d)")) {
                debugLog("Packed JS 감지됨 -> 해제 시도")
                getPacked(responseText) ?: responseText
            } else {
                debugLog("Packed JS 없음 -> 원본 사용")
                responseText
            }

            // 1차 m3u8 찾기
            val m3u8Regex = Regex("""(https?:\\?\/\\?\/[^"']*?poorcdn\.com[^"']*?\.m3u8[^"']*)""")
            val m3u8Match = m3u8Regex.find(unpackedBody)
            
            if (m3u8Match != null) {
                debugLog("m3u8 직접 발견! 성공!")
                val cleanUrl = m3u8Match.value.replace("\\/", "/")
                loadExtractor(cleanUrl, url, subtitleCallback, callback)
                return
            }

            // c.html 토큰 찾기
            val htmlRegex = Regex("""(https?:\\?\/\\?\/[^"']*?poorcdn\.com[^"']*?\.html\?token=[^"']*)""")
            val htmlMatch = htmlRegex.find(unpackedBody)
            
            if (htmlMatch == null) {
                debugLog("실패: c.html 패턴을 찾지 못함.")
                // 소스 일부를 에러 메시지로 띄워서 확인
                throw Error("HTML패턴 실패. 소스앞부분: ${unpackedBody.take(50)}")
            }

            val rawHtmlUrl = htmlMatch.value
            val htmlUrl = rawHtmlUrl.replace("\\/", "/")
            debugLog("c.html 발견: $htmlUrl")

            // c.html 요청
            val finalResponse = app.get(htmlUrl, headers = mapOf("Referer" to url, "User-Agent" to headers["User-Agent"]!!))
            debugLog("c.html 응답 코드: ${finalResponse.code}")
            
            val finalM3u8Match = Regex("""(https?://.*?\.m3u8.*?)["']""").find(finalResponse.text)
            
            if (finalM3u8Match != null) {
                debugLog("최종 m3u8 발견! 성공!")
                loadExtractor(finalM3u8Match.groupValues[1], url, subtitleCallback, callback)
            } else {
                debugLog("실패: 최종 HTML에서 m3u8 못찾음")
                throw Error("최종 m3u8 추출 실패")
            }

        } catch (e: Exception) {
            debugLog("에러 발생: ${e.message}")
            // 화면에 에러를 띄워서 사용자에게 보여줌
            throw Error("디버그: ${e.message}")
        }
    }
}
