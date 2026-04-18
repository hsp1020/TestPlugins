// v8.8 - 로컬 프록시 서버(ProxyWebServer)를 활용한 CDN 403 인증 토큰 우회 방식 적용
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val portalUrl = "https://kingkongtv.org"

    // --- 프록시 서버 관련 변수 ---
    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        
        try {
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc!!)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            val sortedRows = rows.sortedByDescending { row ->
                val isImportant = row.selectFirst("td.subject")?.hasClass("bold") == true
                isImportant
            }
            
            val home = sortedRows.mapNotNull { row ->
                row.toSearchResult(realUrl)
            }

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null

        val channelDiv = this.selectFirst("div.channel_on") ?: return null
        var title = channelDiv.attr("data-title")
        val streamId = channelDiv.attr("data-stream")
        
        if (title.isBlank() || streamId.isBlank()) return null

        if (this.selectFirst("td.subject")?.hasClass("bold") == true) {
            title = "🔥 $title"
        }

        val thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
        val thumbUrl = if (thumbUrlRaw != null) {
            val fixedUrl = fixUrl(refererUrl, thumbUrlRaw)
            val separator = if (fixedUrl.contains("?")) "&" else "?"
            "$fixedUrl${separator}t=${System.currentTimeMillis()}"
        } else ""

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedPoster = URLEncoder.encode(thumbUrl, "UTF-8")
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8")

        val fakeUrl = "$mainUrl/live_stream/$streamId?title=$encodedTitle&poster=$encodedPoster&ref=$encodedRef"

        return newMovieSearchResponse(title, fakeUrl, TvType.Others) {
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val streamId = url.substringAfter("/live_stream/").substringBefore("?")
        
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

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
        val uri = Uri.parse(data)
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val rawRef = uri.getQueryParameter("ref")
        val refererUrl = if (!rawRef.isNullOrBlank()) URLDecoder.decode(rawRef, "UTF-8") else "https://kktv.speed10-1.com/kktv/index.php"
        
        val rawTitle = uri.getQueryParameter("title") ?: ""
        val cleanTitle = rawTitle.replace("🔥 ", "")
        val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
        
        val playerPageUrl = "https://kktv.speed10-1.com/kktv/theme/kktv/pages/pc/pc_view.php?ch=$streamId&title=$encodedTitle&start=on&num=1"
        println("KKTV_DEBUG [Phase 1] Target Player URL: $playerPageUrl")
        
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to refererUrl,
            "Origin" to "https://kktv.speed10-1.com"
        )

        try {
            val response = app.get(playerPageUrl, headers = headers).text
            
            println("KKTV_DEBUG [RAW_RESPONSE_START] =======================================")
            println(response)
            println("KKTV_DEBUG [RAW_RESPONSE_END] =======================================")
            
            // 1. 원본 스트림 주소 추출 (보통 .sdp로 내려옴)
            var rawStreamUrl = Regex("""['"](http[^'"]+\.(?:sdp|m3u8)[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            
            if (rawStreamUrl == null) {
                val webrtcMatch = Regex("""['"](webrtc://[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (webrtcMatch != null) {
                    rawStreamUrl = webrtcMatch.replace("webrtc://", "https://")
                }
            }

            if (rawStreamUrl == null) {
                rawStreamUrl = "https://cdnnowstream.com/cdnnow/$streamId.sdp?site=kktv"
                println("KKTV_DEBUG [Phase 2] 추출 실패. 하드코딩 URL 사용: $rawStreamUrl")
            } else {
                println("KKTV_DEBUG [Phase 2] 원본 스트림 URL 추출 성공: $rawStreamUrl")
            }

            // 2. HTTP-FLV 형식으로 변환 (ExoPlayer가 가장 안정적으로 재생 가능하며 토큰 검증 통과 가능성 높음)
            // m3u8은 프록시를 태워도 서버 설정에 따라 차단될 수 있으므로 FLV 우회가 우선.
            var flvTargetUrl = rawStreamUrl.replace(".sdp", ".flv").replace(".m3u8", ".flv")
            if (flvTargetUrl.contains("play.ogtv3.com") && flvTargetUrl.startsWith("https://")) {
                flvTargetUrl = flvTargetUrl.replaceFirst("https://", "http://")
            }

            println("KKTV_DEBUG [Phase 3] 프록시 타겟 FLV URL: $flvTargetUrl")

            // 3. 가짜 m3u8 플레이리스트 생성 (ExoPlayer를 속이기 위함)
            val fakeM3u8Playlist = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:10
                #EXTINF:10.000,
                $flvTargetUrl
            """.trimIndent()

            // 4. 로컬 프록시 서버 구동 및 플레이리스트 업데이트
            synchronized(this) { 
                currentProxyServer?.stop()
                currentProxyServer = null 
            }
            
            val proxy = ProxyWebServer().apply { 
                updatePlaylist(fakeM3u8Playlist)
                start() 
            }
            currentProxyServer = proxy

            // 5. ExoPlayer에게 넘겨줄 프록시 로컬 호스트 주소
            val proxyUrl = "http://127.0.0.1:${proxy.port}/$streamId/playlist.m3u8"
            println("KKTV_DEBUG [Result] 로컬 프록시 서버 구동 완료. Proxy URL: $proxyUrl")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name (Proxy - FLV 우회)",
                    url = proxyUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://kktv.speed10-1.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin" to "https://kktv.speed10-1.com",
                        "Referer" to "https://kktv.speed10-1.com/",
                        "User-Agent" to userAgent
                    )
                }
            )

        } catch (e: Exception) {
            println("KKTV_DEBUG [Error] loadLinks 예외 발생: ${e.message}")
            e.printStackTrace()
        }

        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try {
            val baseUri = java.net.URI(baseUrl)
            val resolved = baseUri.resolve(relativeUrl)
            resolved.toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }

    // --- 프록시 서버 클래스 내부 구현 ---
    class ProxyWebServer {
        var port: Int = 0
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        @Volatile private var playlistData: String = ""

        fun updatePlaylist(data: String) {
            playlistData = data
        }

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                CoroutineScope(Dispatchers.IO).launch { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) { 
                e.printStackTrace()
            }
        }

        fun stop() { 
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {} 
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val path = line.split(" ")[1]

                while (true) {
                    val headerLine = reader.readLine()
                    if (headerLine.isNullOrEmpty()) break
                }

                val out = socket.getOutputStream()

                // 앱(ExoPlayer)이 m3u8을 요청하면 생성해둔 가짜 플레이리스트 반환
                if (path.contains("playlist.m3u8")) {
                    val payload = playlistData.toByteArray(charset("UTF-8"))
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\nContent-Length: ${payload.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    out.write(payload)
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                }
                out.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }
}
