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
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    // 로컬 서버 싱글톤 (앱 내부에 작은 서버를 띄워 file:// 제한을 우회)
    companion object {
        private var localServer: SimpleWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("=== [MovieKing] getUrl Start (Fix 3001) ===")
        
        try {
            // 1. 초기 요청 헤더 (일단 기본 헤더로 시작)
            var headers = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            // 2. WebView로 페이지 로드 (토큰 발급을 위해 필수)
            // WebViewResolver가 시스템 UA를 자동으로 사용하도록 둡니다.
            val playerResponse = try {
                app.get(
                    url,
                    headers = headers,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                // WebView 실패 시에도 일단 진행 (이미 유효한 url일 수 있음)
                app.get(url, headers = headers)
            }

            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            headers["Cookie"] = cookieString

            // 3. data-m3u8 주소 추출
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

            // 4. [핵심 해결책] 토큰에서 서버가 원하는 User-Agent 추출
            // 추정하지 않고, 토큰에 박힌 값을 그대로 읽어서 사용합니다.
            val targetUA = extractUserAgentFromToken(m3u8Url)
            
            if (targetUA != null) {
                println("[MovieKing] Extracted UA from Token: $targetUA")
                headers["User-Agent"] = targetUA
            } else {
                // 토큰에 UA가 없으면 안전한 모바일 UA 사용
                println("[MovieKing] UA not found in token, using default")
                headers["User-Agent"] = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            // 5. M3U8 원본 다운로드 (추출한 올바른 UA 사용)
            val m3u8Response = app.get(m3u8Url, headers = headers)
            var m3u8Content = m3u8Response.text

            // 6. 키(Key) 처리
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                
                // 키 다운로드 (동일 헤더 사용)
                val keyResponse = app.get(keyUrl, headers = headers)
                val actualKeyBytes = decryptKeyFromJson(keyResponse.text)

                if (actualKeyBytes != null) {
                    println("[MovieKing] Key decrypted successfully")

                    // 로컬 서버 구동 (아직 없으면 생성)
                    if (localServer == null) {
                        localServer = SimpleWebServer()
                    }
                    
                    // 서버에 현재 재생할 파일 내용 등록
                    val port = localServer!!.updateContent(m3u8Content, actualKeyBytes)
                    
                    // 로컬 호스트 주소 생성 (http://127.0.0.1:xxxx/key.bin)
                    val localKeyUrl = "http://127.0.0.1:$port/key.bin"
                    
                    // M3U8 변조: 키 주소를 로컬 서버 주소로 변경
                    m3u8Content = m3u8Content.replace(keyMatch.groupValues[1], localKeyUrl)
                    
                    // 세그먼트 절대 경로 변환 (필수)
                    val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
                    m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                        if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http")) {
                            "$baseUrl$line"
                        } else {
                            line
                        }
                    }
                    
                    // 수정된 M3U8도 로컬 서버에 업데이트
                    localServer!!.updateContent(m3u8Content, actualKeyBytes)
                    
                    val localPlaylistUrl = "http://127.0.0.1:$port/video.m3u8"
                    println("[MovieKing] Serving at: $localPlaylistUrl")

                    // 7. 재생 요청
                    // URL은 http://127.0.0.1... 이므로 Cronet 문제 없음
                    // 헤더(Token에서 추출한 UA)를 전달하여 원격 세그먼트 요청 시 사용하게 함
                    callback(
                        newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://player-v1.bcbc.red/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    return 
                }
            }

            // Fallback (키가 없는 경우)
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing] Critical Error: ${e.message}")
        }
    }

    // --- Helper Functions ---

    // 토큰(JWT) 내부를 디코딩하여 User-Agent를 추출하는 함수
    private fun extractUserAgentFromToken(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/").split(".").getOrNull(1) ?: return null
            val payload = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            // "ua":"값" 패턴 찾기
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
    
    // --- Simple Local Web Server ---
    class SimpleWebServer {
        private var serverSocket: ServerSocket? = null
        private var currentM3u8: String = ""
        private var currentKey: ByteArray = ByteArray(0)
        private var isRunning = false
        var port: Int = 0

        fun updateContent(m3u8: String, key: ByteArray): Int {
            currentM3u8 = m3u8
            currentKey = key
            
            if (!isRunning) {
                start()
            }
            return port
        }

        private fun start() {
            try {
                serverSocket = ServerSocket(0) // Random port
                port = serverSocket?.localPort ?: 0
                isRunning = true
                
                thread(start = true, isDaemon = true) {
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                        try {
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            // Socket closed or error
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
                    val requestLine = reader.readLine()
                    
                    if (requestLine != null) {
                        val parts = requestLine.split(" ")
                        if (parts.size >= 2) {
                            val path = parts[1]
                            val output = socket.getOutputStream()
                            val writer = PrintWriter(output)

                            // CORS 헤더 추가 (중요: 플레이어 접근 허용)
                            writer.print("HTTP/1.1 200 OK\r\n")
                            writer.print("Access-Control-Allow-Origin: *\r\n")
                            
                            if (path.contains("video.m3u8")) {
                                val data = currentM3u8.toByteArray()
                                writer.print("Content-Type: application/vnd.apple.mpegurl\r\n")
                                writer.print("Content-Length: ${data.size}\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("\r\n")
                                writer.flush()
                                output.write(data)
                                output.flush()
                            } else if (path.contains("key.bin")) {
                                writer.print("Content-Type: application/octet-stream\r\n")
                                writer.print("Content-Length: ${currentKey.size}\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("\r\n")
                                writer.flush()
                                output.write(currentKey)
                                output.flush()
                            } else {
                                writer.print("HTTP/1.1 404 Not Found\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("\r\n")
                                writer.flush()
                            }
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
