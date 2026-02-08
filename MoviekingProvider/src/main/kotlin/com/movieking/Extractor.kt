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
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    // 서버가 모바일로 인식하도록 최신 모바일 Chrome UA 고정
    private val FORCED_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

    // [핵심] 재생하는 동안 데이터를 제공할 로컬 서버 (싱글톤 유지)
    companion object {
        private var localServer: SimpleWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("=== [MovieKing] getUrl Start (Localhost Mode) ===")
        
        try {
            // 1. 헤더 설정
            val headers = mutableMapOf(
                "User-Agent" to FORCED_UA,
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            // 2. 플레이어 페이지 로드 (WebViewResolver)
            val playerResponse = try {
                app.get(
                    url,
                    headers = headers,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                app.get(url, headers = headers)
            }

            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            headers["Cookie"] = cookieString

            // 3. data-m3u8 추출
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

            // 4. M3U8 다운로드
            val m3u8Response = app.get(m3u8Url, headers = headers)
            var m3u8Content = m3u8Response.text

            // 5. 키(Key) 처리
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = headers)
                val actualKeyBytes = decryptKeyFromJson(keyResponse.text)

                if (actualKeyBytes != null) {
                    println("[MovieKing] Key decrypted successfully")

                    // [핵심] 로컬 서버 구동 (포트 0 = 랜덤 포트 할당)
                    if (localServer == null) {
                        localServer = SimpleWebServer()
                    }
                    // 서버에 현재 재생할 파일 내용 등록
                    val port = localServer!!.updateContent(m3u8Content, actualKeyBytes)
                    
                    // 로컬 호스트 주소 생성
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

                    // 6. 재생 요청
                    // URL은 http://127.0.0.1... 이므로 Cronet이 받아들임
                    // 헤더(UA, Cookie)는 그대로 전달되어 세그먼트(https://...) 요청 시 사용됨
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

            // Fallback
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

                            if (path.contains("video.m3u8")) {
                                val data = currentM3u8.toByteArray()
                                writer.print("HTTP/1.1 200 OK\r\n")
                                writer.print("Content-Type: application/vnd.apple.mpegurl\r\n")
                                writer.print("Content-Length: ${data.size}\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("\r\n")
                                writer.flush()
                                output.write(data)
                                output.flush()
                            } else if (path.contains("key.bin")) {
                                writer.print("HTTP/1.1 200 OK\r\n")
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
