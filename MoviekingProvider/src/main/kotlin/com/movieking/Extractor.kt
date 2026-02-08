package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        // 싱글톤 프록시 서버
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // [중요] 이 로그가 보여야 새 코드가 적용된 것입니다.
        println("=== [MovieKing] getUrl Start (Full Proxy Mode) ===")
        
        try {
            // 1. 기본 헤더 준비
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            // 2. WebView로 페이지 접속 (쿠키/세션 생성)
            val playerResponse = try {
                app.get(
                    url,
                    headers = baseHeaders,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                println("[MovieKing] WebView failed, falling back: ${e.message}")
                app.get(url, headers = baseHeaders)
            }

            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // 쿠키 헤더 등록
            baseHeaders["Cookie"] = cookieString
            println("[MovieKing] Cookie acquired.")

            // 3. M3U8 주소 파싱
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: run {
                    println("[MovieKing] Error: data-m3u8 not found")
                    return
                }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }
            println("[MovieKing] M3U8 URL: $m3u8Url")

            // 4. 토큰에서 올바른 User-Agent 추출 (필수)
            val extractedUA = extractUserAgentFromToken(m3u8Url)
            // 추출 실패 시 최신 모바일 UA 사용
            val targetUA = extractedUA ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            
            baseHeaders["User-Agent"] = targetUA
            println("[MovieKing] Target User-Agent: $targetUA")

            // 5. M3U8 다운로드
            val m3u8Response = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = m3u8Response.text

            // 6. 키(Key) 처리
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var actualKeyBytes: ByteArray? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                actualKeyBytes = decryptKeyFromJson(keyResponse.text)
                
                if (actualKeyBytes != null) {
                    println("[MovieKing] Key decrypted.")
                }
            }

            // 7. 프록시 서버 시작 및 세션 업데이트
            if (proxyServer == null) {
                proxyServer = ProxyWebServer()
            }
            // 헤더와 키를 프록시 서버에 전달
            val port = proxyServer!!.updateSession(baseHeaders, actualKeyBytes)
            val proxyBaseUrl = "http://127.0.0.1:$port"

            // 8. M3U8 내용 변조 (모든 경로를 프록시 경유하도록 변경)
            
            // (1) 키 경로 변경
            if (keyMatch != null && actualKeyBytes != null) {
                val localKeyUrl = "$proxyBaseUrl/key.bin"
                m3u8Content = m3u8Content.replace(keyMatch.groupValues[1], localKeyUrl)
            }

            // (2) 세그먼트 경로 변경 (핵심: 프록시 태우기)
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    // 원격 세그먼트의 절대 주소 계산
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    // 프록시 URL로 포장: http://127.0.0.1:port/proxy?url=인코딩된주소
                    val encodedUrl = URLEncoder.encode(segmentUrl, "UTF-8")
                    "$proxyBaseUrl/proxy?url=$encodedUrl"
                } else {
                    line
                }
            }

            // 9. 변조된 M3U8을 프록시 서버에 등록
            proxyServer!!.setPlaylist(m3u8Content)
            
            val localPlaylistUrl = "$proxyBaseUrl/playlist.m3u8"
            println("[MovieKing] Proxy ready at: $localPlaylistUrl")

            // 10. 재생 요청 (플레이어는 로컬 주소만 봄 -> Cronet 문제 없음)
            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    // 프록시가 헤더를 관리하므로 여기선 기본값만 줘도 무방
                    this.headers = baseHeaders
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing] Exception: ${e.message}")
        }
    }

    // --- Helper Functions ---

    private fun extractUserAgentFromToken(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/").split(".").getOrNull(1) ?: return null
            val payload = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val uaMatch = Regex(""""ua"\s*:\s*"([^"]+)"""").find(payload)
            uaMatch?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptKeyFromJson(jsonText: String): ByteArray? {
        return try {
            val decodedJsonStr = try {
                String(Base64.decode(jsonText, Base64.DEFAULT))
            } catch (e: Exception) {
                jsonText 
            }
            val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
            val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null

            val encryptedBytes = Base64.decode(encKeyB64, Base64.DEFAULT)
            val cleanBytes = encryptedBytes.drop(2).toByteArray()

            val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
            val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
            val permutation = permString.split(",").map { it.trim().toInt() }

            val segments = listOf(
                cleanBytes.copyOfRange(0, 4),
                cleanBytes.copyOfRange(4, 8),
                cleanBytes.copyOfRange(8, 12),
                cleanBytes.copyOfRange(12, 16)
            )

            val resultKey = ByteArray(16)
            var offset = 0
            for (idx in permutation) {
                val seg = segments[idx]
                System.arraycopy(seg, 0, resultKey, offset, 4)
                offset += 4
            }
            resultKey
        } catch (e: Exception) {
            null
        }
    }
    
    // --- Full Proxy Web Server Class ---
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        // 데이터 저장소 (세션별 헤더 및 데이터)
        private var currentHeaders: Map<String, String> = emptyMap()
        private var currentKey: ByteArray? = null
        private var currentPlaylist: String = ""

        fun updateSession(headers: Map<String, String>, key: ByteArray?): Int {
            currentHeaders = headers
            currentKey = key
            if (!isRunning) start()
            return port
        }

        fun setPlaylist(m3u8: String) {
            currentPlaylist = m3u8
        }

        private fun start() {
            try {
                serverSocket = ServerSocket(0) // Random port
                port = serverSocket?.localPort ?: 0
                isRunning = true
                println("[MovieKing] Proxy Server started on port $port")
                
                thread(start = true, isDaemon = true) {
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                        try {
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            // Socket Error
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun handleClient(socket: Socket) {
            thread(start = true) {
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val requestLine = reader.readLine() ?: return@thread
                    
                    val parts = requestLine.split(" ")
                    if (parts.size >= 2) {
                        val path = parts[1]
                        val output = socket.getOutputStream()
                        
                        // 1. M3U8 요청
                        if (path.contains("/playlist.m3u8")) {
                            val data = currentPlaylist.toByteArray()
                            val header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                    "Content-Length: ${data.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(data)
                            output.flush()
                        }
                        // 2. Key 파일 요청
                        else if (path.contains("/key.bin") && currentKey != null) {
                            val header = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/octet-stream\r\n" +
                                    "Content-Length: ${currentKey!!.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(currentKey)
                            output.flush()
                        }
                        // 3. 세그먼트 프록시 요청 (핵심)
                        // /proxy?url=https%3A%2F%2F...
                        else if (path.contains("/proxy")) {
                            val urlParam = path.substringAfter("url=").substringBefore(" ")
                            val targetUrl = java.net.URLDecoder.decode(urlParam, "UTF-8")
                            
                            // 원격 서버로 요청 보내기
                            try {
                                val connection = URL(targetUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                
                                // [중요] 저장해둔 완벽한 헤더(UA, Cookie) 주입
                                currentHeaders.forEach { (k, v) -> 
                                    connection.setRequestProperty(k, v) 
                                }
                                connection.connect()

                                val responseCode = connection.responseCode
                                if (responseCode == 200) {
                                    val inputStream = connection.inputStream
                                    
                                    val header = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: video/mp2t\r\n" +
                                            "Connection: close\r\n\r\n"
                                    output.write(header.toByteArray())
                                    
                                    // 데이터 스트리밍
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                    output.flush()
                                    inputStream.close()
                                } else {
                                    val err = "HTTP/1.1 $responseCode Error\r\nConnection: close\r\n\r\n"
                                    output.write(err.toByteArray())
                                }
                                connection.disconnect()
                            } catch (e: Exception) {
                                println("[MovieKing Proxy] Segment Error: ${e.message}")
                            }
                        } else {
                            val err = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                            output.write(err.toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
