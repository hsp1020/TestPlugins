package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.pk"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "Referer" to mainUrl,
        "user-agent" to userAgent
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = tryParseJson<List<Pair<String, String>>>(url)
        Log.d("DDLExt - Links", links?.toJson() ?: "null")
        val extractors = links?.map {
            extractVideo(it.second, it.first)
        } ?: listOf(extractVideo(url))

        extractors.forEach {
            if (it != null) {
                callback(it)
            }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String = this.name): ExtractorLink? {
        if (!url.contains("dlhd")) return null

        val resp = app.post(url, headers = headers).document
        val iframes = resp.select("iframe")
        val iframeSrc = iframes.attr("src")
        if (iframeSrc.isNullOrEmpty()) return null
        
        val url1 = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
        val parsedUrl = URL(url1)
        val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"

        Log.d("DDLExt", url1)
        val finalUrl = (if (url1.contains("vidembed")) extractFromVidembed(url1) else
            extractFromNewzar(url1, refererBase)) ?: return null

        return newExtractorLink(
            sourceName,
            sourceName,
            finalUrl,
            type = ExtractorLinkType.M3U8,
        ) {
            this.referer = "$refererBase/"
            this.quality = Qualities.Unknown.value
            this.headers = mapOf(
                "Referer" to "$refererBase/",
                "Origin" to refererBase,
                "Connection" to "Keep-Alive",
                "User-Agent" to userAgent
            )
        }
    }

    private suspend fun extractFromVidembed(urlNextPage: String): String? {
        val vidembedHost = URL(urlNextPage).host
        val liveId = urlNextPage.substringAfterLast("/").substringBefore("#")
        val liveUrl = "https://www.$vidembedHost/api/source/$liveId?type=live"
        val requestBody = "{\"r\":\"https://thedaddy.top/\",\"d\":\"www.$vidembedHost\"}"
        val referer = if (urlNextPage.contains("//www.")) urlNextPage
        else {
            "https://www." + urlNextPage.substringAfter("https://").substringBefore("#")
        }
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to referer,
            "Origin" to referer.substringBefore("/stream"),
            "X-Requested-With" to "XMLHttpRequest"
        )
        val resp = app.post(liveUrl, headers, referer = referer, json = requestBody).body.string()
        Log.d("DDLExt", liveUrl)
        val data = parseJson<VidembedResponse>(resp)
        Log.d("DDLExt", data.toJson())
        Log.d("DDLExt", data.player)

        return null
    }

    private suspend fun extractFromNewzar(urlNextPage: String, serverUrl: String): String? {
        val page = app.get(urlNextPage, headers).document
        val scriptElement = page.select("script").firstOrNull { 
            it.data().contains("channelKey") || it.data().contains("CHANNEL_KEY") 
        } ?: return null
        val script = scriptElement.data()

        // 1. 채널 키 동적 파싱
        val channelKey = Regex("""channelKey\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
            ?: Regex("""CHANNEL_KEY\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
            ?: return null

        // 2. 인증 파라미터 동적 파싱 및 Fallback 처리
        var authTs = Regex("""authTs\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
            ?: Regex("""authTs\s*=\s*([0-9]+)""").find(script)?.groupValues?.get(1)
        var authRnd = Regex("""authRnd\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
        var authSig = Regex("""authSig\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)

        if (authTs == null || authRnd == null || authSig == null) {
            val bundleBase64 = Regex("""IJXX\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
            if (bundleBase64 != null) {
                val bundle = base64Decode(bundleBase64)
                val bundleObj = tryParseJson<Bundle>(bundle)
                if (bundleObj != null) {
                    authTs = base64Decode(bundleObj.bTs)
                    authRnd = base64Decode(bundleObj.bRnd)
                    authSig = base64Decode(bundleObj.bSig)
                }
            }
        }

        if (authTs == null || authRnd == null || authSig == null) {
            Log.d("DDLExt", "Required authentication parameters missing.")
            return null
        }

        val authHost = Regex("""(https?://[^\s]+/auth\.php\?channel_id=)""").find(script)?.groupValues?.get(1)
            ?: "https://top2new.newkso.ru/auth.php?channel_id="

        val params = mapOf(
            "channel_id" to channelKey,
            "ts" to authTs,
            "rnd" to authRnd,
            "sig" to authSig,
        )
        Log.d("DDLExt", "Params: $params")

        // 3. auth.php 세션 인증 트리거 호출
        val authResponse = app.get(
            "$authHost$channelKey",
            params = params,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$serverUrl/",
                "Origin" to serverUrl,
                "Connection" to "Keep-Alive"
            )
        )
        Log.d("DDLExt", "Auth Status Code: ${authResponse.code}")
        if (authResponse.code == 403) return null

        // 4. server_lookup.php 호출
        val serverKeyResp = app.get("$serverUrl/server_lookup.php?channel_id=$channelKey").body.string()
        Log.d("DDLExt", "Server Lookup response: $serverKeyResp")
        val data = try { 
            parseJson<DataResponse>(serverKeyResp) 
        } catch (e: Exception) {
            Log.d("DDLExt", "Mismatched server key payload: $e")
            return null
        }

        // 5. iframe 내부의 HLS 호스트 주소 파싱
        val host = Regex("""serverKey\s*\+\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1) 
            ?: "new.newkso.ru"

        val serverKey = data.serverKey
        val m3u8Url = if (serverKey == "top1/cdn") {
            "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
        } else {
            "https://${serverKey}new.newkso.ru/${serverKey}/$channelKey/mono.m3u8"
        }
        Log.d("DDLExt", "Assembled m3u8 Source: $m3u8Url")

        // 6. HTTP 리다이렉트(302) 추적하여 최종 md5 & expires 주소 도출
        val streamHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$serverUrl/",
            "Origin" to serverUrl,
            "Connection" to "Keep-Alive"
        )
        
        // 헤더 누실로 인한 403 인증 실패를 막기 위해 Redirect를 일시 차단 후 location을 안전하게 추출
        val streamResponse = app.get(m3u8Url, headers = streamHeaders, allowRedirects = false)
        val location = streamResponse.headers["Location"] ?: streamResponse.headers["location"]
        val finalUrl = if (!location.isNullOrEmpty()) {
            if (location.startsWith("/")) {
                val parsedM3u8 = URL(m3u8Url)
                "${parsedM3u8.protocol}://${parsedM3u8.host}$location"
            } else {
                location
            }
        } else {
            streamResponse.url
        }

        Log.d("DDLExt", "Resolved Stream URL: $finalUrl")
        return finalUrl
    }

    data class DataResponse(@JsonProperty("server_key") val serverKey: String)
    data class VidembedResponse(
        val success: Boolean,
        val player: String
    )
    data class Bundle(
        @JsonProperty("b_host")
        val bHost: String,
        @JsonProperty("b_rnd")
        val bRnd: String,
        @JsonProperty("b_script")
        val bScript: String,
        @JsonProperty("b_sig")
        val bSig: String,
        @JsonProperty("b_ts")
        val bTs: String
    )
}
