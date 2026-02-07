package com.movieking

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import java.io.File
import java.net.URI
import java.security.MessageDigest

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    private val TAG = "BcbcRedExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val userAgent =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

            Log.d(TAG, "Start getUrl for: $url")

            // 1) Open iframe/player page with WebViewResolver to create WebView cookies
            val pageResp = app.get(
                url,
                referer = referer,
                headers = mapOf("User-Agent" to userAgent),
                interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
            )
            val pageText = pageResp.text
            Log.d(TAG, "Loaded player page, length=${pageText?.length ?: "null"}")

            // 2) Extract data-m3u8 (정확한 패턴에 맞춤)
            // 예: data-m3u8="https://player.bcbc.red/stream/....m3u8" 또는 data-m3u8='...'
            val m3u8Regex = Regex("""data-m3u8\s*=\s*["'](.*?)["']""")
            val m3u8Match = m3u8Regex.find(pageText ?: "")
            if (m3u8Match == null) {
                Log.e(TAG, "m3u8 not found in page")
                return
            }
            val m3u8Url = m3u8Match.groupValues[1].replace("\\\\/", "/").trim()
            Log.d(TAG, "Found m3u8Url: $m3u8Url")

            // 3) Build headers (User-Agent, Referer, Origin/Host, Cookie)
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to url, // iframe URL (원래 호출한 url)
                "Accept" to "*/*"
            )

            // 정확한 origin/referer/host 맞추기
            try {
                val parsedM3u8 = URI(m3u8Url)
                headers["Origin"] = "${parsedM3u8.scheme}://${parsedM3u8.host}"
                headers["Host"] = parsedM3u8.host
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse m3u8 URI for Origin/Host: ${e.message}")
            }

            // 4) CookieManager에서 쿠키 추출 (WebViewResolver로 생성된 쿠키 사용)
            val cookieManager = CookieManager.getInstance()
            val cookieForDomain = try {
                // 쿠키를 얻을 때는 도메인 전체(https://host)로 요청
                val parsedHost = URI(m3u8Url).host
                cookieManager.getCookie("https://${parsedHost}") ?: ""
            } catch (e: Exception) {
                Log.w(TAG, "Cookie extraction failed: ${e.message}")
                ""
            }
            if (cookieForDomain.isNotEmpty()) {
                headers["Cookie"] = cookieForDomain
                Log.d(TAG, "Got cookies length=${cookieForDomain.length}")
            } else {
                Log.d(TAG, "No cookies found from CookieManager")
            }

            // 5) 가져온 m3u8 원문 읽기
            Log.d(TAG, "Fetching m3u8 with headers: ${headers.keys}")
            val m3u8Resp = app.get(m3u8Url, referer = url, headers = headers)
            val m3u8Text = m3u8Resp.text ?: ""
            Log.d(TAG, "m3u8 fetched, length=${m3u8Text.length}")

            // 6) EXT-X-KEY 치환 준비
            val keyLineRegex = Regex("""#EXT-X-KEY:([^\r\n]+)""")
            var rewritten = m3u8Text
            val foundKeys = keyLineRegex.findAll(m3u8Text).toList()
            Log.d(TAG, "Found ${foundKeys.size} EXT-X-KEY lines")

            for (match in foundKeys) {
                val attrs = match.groupValues[1]
                val uriRegex = Regex("""URI\s*=\s*"(.*?)"""")
                val uriMatch = uriRegex.find(attrs)
                if (uriMatch == null) {
                    Log.w(TAG, "No URI found in key attrs: $attrs")
                    continue
                }
                val keyUriRaw = uriMatch.groupValues[1]
                val base = try { URI(m3u8Url) } catch (e: Exception) { null }
                val absKeyUri = try {
                    if (base != null) base.resolve(keyUriRaw).toString()
                    else keyUriRaw
                } catch (e: Exception) {
                    keyUriRaw
                }
                Log.d(TAG, "Key URI resolved: $absKeyUri")

                // 7) 키 바이너리 직접 요청 (바이트 응답)
                // app.get의 바이트 반환 방식이 다르면 여기 조정 필요할 수 있음.
                Log.d(TAG, "Fetching key bytes...")
                val keyResp = try {
                    // 시도 1: byteResponse 옵션 사용 (예시)
                    app.get(absKeyUri, referer = url, headers = headers, byteResponse = true)
                } catch (e: Exception) {
                    Log.w(TAG, "app.get(byteResponse) failed: ${e.message}. Trying fallback text fetch.")
                    try {
                        app.get(absKeyUri, referer = url, headers = headers)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to fetch key: ${e2.message}")
                        null
                    }
                }

                var keyBytes: ByteArray? = null
                var contentType: String? = null
                try {
                    if (keyResp != null) {
                        // 여러 구현체를 고려: response.bytes, response.data, response.bodyBytes 등
                        keyBytes = when {
                            keyResp::class.members.any { it.name == "bytes" } -> {
                                val prop = keyResp::class.members.first { it.name == "bytes" }
                                prop.call(keyResp) as? ByteArray
                            }
                            keyResp::class.members.any { it.name == "byteArray" } -> {
                                val prop = keyResp::class.members.first { it.name == "byteArray" }
                                prop.call(keyResp) as? ByteArray
                            }
                            keyResp::class.members.any { it.name == "data" } -> {
                                val prop = keyResp::class.members.first { it.name == "data" }
                                prop.call(keyResp) as? ByteArray
                            }
                            else -> null
                        }
                        // content-type 시도 추출
                        try {
                            contentType = keyResp::class.members.firstOrNull { it.name.equals("contentType", true) }?.call(keyResp)?.toString()
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reflection-based key bytes extraction failed: ${e.message}")
                }

                // fallback: 만약 keyBytes가 null, app.get으로 텍스트 받아서 바이트로 변환 시도
                if (keyBytes == null) {
                    try {
                        val textFallback = app.get(absKeyUri, referer = url, headers = headers).text ?: ""
                        keyBytes = textFallback.toByteArray(Charsets.UTF_8)
                        Log.d(TAG, "Used text fallback for key (length=${keyBytes.size}) contentType=$contentType")
                    } catch (e: Exception) {
                        Log.w(TAG, "key text fallback failed: ${e.message}")
                    }
                }

                if (keyBytes == null) {
                    Log.e(TAG, "Failed to obtain key bytes for $absKeyUri; skipping replacement for this key")
                    continue
                }

                Log.d(TAG, "Key bytes length=${keyBytes.size} contentType=$contentType")

                // 8) 키 길이 검사 (AES-128은 16바이트)
                if (keyBytes.size == 16) {
                    val b64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                    val dataUri = "data:application/octet-stream;base64,$b64"

                    // 정확한 줄 치환: 현재 match.value는 "#EXT-X-KEY:...."
                    val oldLine = match.value
                    val newLine = oldLine.replace(uriMatch.value, """URI="$dataUri"""")
                    rewritten = rewritten.replaceFirst(oldLine, newLine)
                    Log.d(TAG, "Replaced key URI with data URI (base64 length=${b64.length})")
                } else {
                    // 만약 HTML(403 페이지 등)이 온 경우 디버깅 로그를 충분히 남김
                    Log.w(TAG, "Key length != 16 (=${keyBytes.size}). Likely error page returned. Content sample: ${
                        try {
                            val sample = keyBytes.take(200).toByteArray().toString(Charsets.UTF_8)
                            sample.replace("\n", "\\n").replace("\r", "\\r")
                        } catch (e: Exception) { "unprintable" }
                    }")
                    // 대안: 계속 다른 헤더(예: 다른 referer/origin) 시도하거나, 실패 처리
                }
            }

            // 9) 치환 후 결과를 캐시에 저장하고 file:// URI로 전달
            val cacheDir = app.context.cacheDir
            // 파일명은 m3u8Url 해시로
            val fname = "cs_m3u8_" + sha1Hex(m3u8Url) + ".m3u8"
            val outFile = File(cacheDir, fname)
            outFile.writeText(rewritten)
            Log.d(TAG, "Wrote modified m3u8 to ${outFile.absolutePath} (size=${outFile.length()})")

            val fileUri = "file://${outFile.absolutePath}"

            // 10) 콜백으로 로컬 파일 전달 (내부적으로 key는 data:로 인라인되어 외부 키 요청 없음)
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fileUri,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    // 로컬 파일이므로 네트워크 헤더는 필요없음 — 하지만 디버깅을 위해 남겨둬도 됨
                    this.headers = mapOf("User-Agent" to userAgent, "Referer" to url)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled error in getUrl: ${e.stackTraceToString()}")
        }
    }

    private fun sha1Hex(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
}
