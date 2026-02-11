package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

// [v173] Extractor.kt: "멀티 헤더 오토-매칭" - 16바이트 키가 나올 때까지 헤더를 바꿔가며 전수 조사
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, thumbnailHint: String? = null): Boolean {
        println("[BunnyPoorCdn] v173 시작 - 멀티 헤더 오토-매칭 시스템 가동")
        
        // 1. URL 및 Referer 준비
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"
        val videoId = "video_${System.currentTimeMillis()}"

        // 2. M3U8 주소(c.html) 확보
        val resolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""), useOkhttp = false, timeout = 30000L)
        var targetUrl: String? = null
        try {
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA), interceptor = resolver)
            if (response.url.contains("/c.html")) targetUrl = response.url
        } catch (e: Exception) { }

        if (targetUrl == null) return false

        // 3. M3U8 다운로드
        try {
            val m3u8Response = app.get(targetUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to cleanUrl))
            val m3u8Content = m3u8Response.text
            if (!m3u8Content.contains("#EXTM3U")) return false

            val m3u8Uri = URI(targetUrl)
            val tokenQuery = m3u8Uri.rawQuery

            // 4. 프록시 서버 시작
            // [핵심] 프록시에게 "성공 가능한 모든 리퍼러 후보"를 전달
            val refererCandidates = listOf(
                cleanUrl,                               // 1순위: 토큰 포함 전체 주소
                cleanUrl.substringBefore("?"),          // 2순위: 쿼리 제거 주소
                "https://player.bunny-frame.online/",   // 3순위: 플레이어 루트
                "https://tvwiki5.net/",                 // 4순위: 메인 사이트
                "https://every9.poorcdn.com/"           // 5순위: CDN 자체
            )

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                // 헤더 후보군 등록
                setHeadersList(refererCandidates, DESKTOP_UA)
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            
            // 5. M3U8 변조 (토큰 전파)
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    if (uriMatch != null) {
                        val originalPath = uriMatch.groupValues[1]
                        var absUrl = m3u8Uri.resolve(originalPath).toString()
                        if (!absUrl.contains("token=") && !tokenQuery.isNullOrEmpty()) {
                            absUrl += if (absUrl.contains("?")) "&$tokenQuery" else "?$tokenQuery"
                        }
                        // 키 요청을 프록시로 돌림
                        newLines.add(line.replace(originalPath, "$proxyRoot/key?url=${URLEncoder.encode(absUrl, "UTF-8")}"))
                    } else { newLines.add(line) }
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    var absUrl = m3u8Uri.resolve(line).toString()
                    if (!absUrl.contains("token=") && !tokenQuery.isNullOrEmpty()) {
                        absUrl += if (absUrl.contains("?")) "&$tokenQuery" else "?$tokenQuery"
                    }
                    newLines.add("$proxyRoot/video?url=${URLEncoder.encode(absUrl, "UTF-8")}")
                } else { newLines.add(line) }
            }

            proxyServer!!.setPlaylist(newLines.joinToString("\n"))

            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                this.referer = cleanUrl 
                this.quality = Qualities.Unknown.value
            })
            return true
        } catch (e: Exception) { }
        return false
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentPlaylist: String = ""
        
        // 헤더 후보군 저장소
        private var refererCandidates: List<String> = emptyList()
        private var userAgent: String = ""

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) {
                    while (isRunning && serverSocket != null) {
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) { }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }

        fun setHeadersList(referers: List<String>, ua: String) {
            refererCandidates = referers
            userAgent = ua
        }

        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    val bytes = currentPlaylist.toByteArray()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                    output.write(bytes)
                } else if (path.contains("/key")) {
                    handleKeyRequest(path, output)
                } else if (path.contains("/video")) {
                    // 비디오는 1순위 헤더로 그냥 요청 (비디오는 보통 관대함, 키가 문제임)
                    handleVideoRequest(path, output)
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        // [v173 핵심] 키 요청 처리: 16바이트가 나올 때까지 헤더 돌려가며 시도
        private fun handleKeyRequest(path: String, output: OutputStream) {
            try {
                val urlParam = path.substringAfter("url=").substringBefore(" ")
                val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                
                var successKey: ByteArray? = null
                
                // 후보군 순회
                for (ref in refererCandidates) {
                    val headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to ref,
                        "Accept" to "application/octet-stream, */*", // 바이너리 요청
                        "Connection" to "keep-alive"
                        // Origin은 일단 제거 (가장 확률 높음)
                    )
                    
                    try {
                        println("[BunnyPoorCdn Proxy] 키 시도 중... Referer: $ref")
                        val response = runBlocking { app.get(targetUrl, headers = headers) }
                        if (response.isSuccessful) {
                            val bytes = response.body.bytes()
                            if (bytes.size == 16) {
                                println("[BunnyPoorCdn Proxy] ★ 키 획득 성공! (16 bytes)")
                                successKey = bytes
                                break // 성공하면 루프 탈출
                            } else {
                                println("[BunnyPoorCdn Proxy] 실패: ${bytes.size} bytes (예상: 16)")
                            }
                        }
                    } catch (e: Exception) { }
                }

                if (successKey != null) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\n\r\n".toByteArray())
                    output.write(successKey)
                } else {
                    println("[BunnyPoorCdn Proxy] 모든 헤더 조합 실패. 403 리턴.")
                    output.write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
                }
            } catch (e: Exception) { }
        }

        private fun handleVideoRequest(path: String, output: OutputStream) {
            try {
                val urlParam = path.substringAfter("url=").substringBefore(" ")
                val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                
                // 비디오는 1순위(cleanUrl) 헤더 사용
                val headers = mapOf("User-Agent" to userAgent, "Referer" to refererCandidates.firstOrNull() ?: "", "Accept" to "*/*")
                
                runBlocking {
                    val response = app.get(targetUrl, headers = headers)
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                        output.write(bytes)
                    } else {
                        output.write("HTTP/1.1 ${response.code} Error\r\n\r\n".toByteArray())
                    }
                }
            } catch (e: Exception) { }
        }
    }
}
