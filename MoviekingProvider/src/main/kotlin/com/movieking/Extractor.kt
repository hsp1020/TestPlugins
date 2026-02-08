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
import java.io.InputStream
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("=== [MovieKing v37] getUrl Start (Final Fix: v32 Key + XOR) ===")
        
        try {
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            try {
                app.get(
                    url,
                    headers = baseHeaders,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                app.get(url, headers = baseHeaders)
            }

            val m3u8Response = app.get(url, headers = baseHeaders)
            val playerHtml = m3u8Response.text
            val cookies = m3u8Response.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            baseHeaders["Cookie"] = cookieString

            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: run {
                    println("[MovieKing v37] Error: data-m3u8 not found")
                    return
                }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            val chromeVersion = extractChromeVersion(m3u8Url) ?: "124.0.0.0"
            val standardUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Mobile Safari/537.36"
            baseHeaders["User-Agent"] = standardUA
            println("[MovieKing v37] UA: $standardUA")

            val playlistResponse = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = playlistResponse.text

            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var actualKeyBytes: ByteArray? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                
                // [정답 로직] v32의 바이트 처리 방식 사용
                actualKeyBytes = decryptKeyFromBytes(keyResponse.text)
                
                if (actualKeyBytes != null) {
                    val keyHex = actualKeyBytes.joinToString("") { "%02X".format(it) }
                    println("[MovieKing v37] Final Key (Hex): $keyHex")
                }
            }

            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            val port = proxyServer!!.updateSession(baseHeaders, actualKeyBytes)
            val proxyBaseUrl = "http://127.0.0.1:$port"

            if (keyMatch != null && actualKeyBytes != null) {
                val localKeyUrl = "$proxyBaseUrl/key.bin"
                m3u8Content = m3u8Content.replace(keyMatch.groupValues[1], localKeyUrl)
            }

            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    val encodedUrl = URLEncoder.encode(segmentUrl, "UTF-8")
                    "$proxyBaseUrl/proxy?url=$encodedUrl"
                } else {
                    line
                }
            }

            proxyServer!!.setPlaylist(m3u8Content)
            val localPlaylistUrl = "$proxyBaseUrl/playlist.m3u8"
            println("[MovieKing v37] Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v37] Error: ${e.message}")
        }
    }

    private fun extractChromeVersion(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/").split(".").getOrNull(1) ?: return null
            val payload = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val uaMatch = Regex(""""ua"\s*:\s*"Chrome\(([^)]+)\)"""").find(payload)
            uaMatch?.groupValues?.get(1)
        } catch (e: Exception) { null }
    }

    // [v37 핵심] v32의 성공 로직 복원 (바이트 슬라이싱)
    private fun decryptKeyFromBytes(jsonText: String): ByteArray? {
        return try {
            val decodedJsonStr = try { String(Base64.decode(jsonText, Base64.DEFAULT)) } catch (e: Exception) { jsonText }
            
            val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
            val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null

            // [중요] 문자열이 아닌 Base64 디코딩된 '바이트'를 사용해야 함
            val encryptedBytes = Base64.decode(encKeyB64, Base64.DEFAULT)
            
            val noiseLenRegex = """"noise_length"\s*:\s*(\d+)""".toRegex()
            val noiseLen = noiseLenRegex.find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 0
            
            val segSizesRegex = """"segment_sizes"\s*:\s*\[([\d,]+)\]""".toRegex()
            val segSizesStr = segSizesRegex.find(ruleJson)?.groupValues?.get(1) ?: "4,4,4,4"
            val segmentSizes = segSizesStr.split(",").map { it.trim().toInt() }
            
            val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
            val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
            val permutation = permString.split(",").map { it.trim().toInt() }

            val cleanSegments = mutableListOf<ByteArray>()
            var currentOffset = 0
            
            for (size in segmentSizes) {
                if (currentOffset + size > encryptedBytes.size) break
                val segment = encryptedBytes.copyOfRange(currentOffset, currentOffset + size)
                cleanSegments.add(segment)
                currentOffset += size + noiseLen
            }

            val finalKey = ByteArray(16)
            var finalOffset = 0
            for (idx in permutation) {
                if (idx < cleanSegments.size) {
                    val seg = cleanSegments[idx]
                    System.arraycopy(seg, 0, finalKey, finalOffset, seg.size)
                    finalOffset += seg.size
                }
            }
            return finalKey
        } catch (e: Exception) { null }
    }
    
    // --- Proxy Web Server (XOR + Full Headers) ---
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentKey: ByteArray? = null
        @Volatile private var currentPlaylist: String = ""

        fun isAlive(): Boolean = isRunning && serverSocket != null && !serverSocket!!.isClosed

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket?.localPort ?: 0
                isRunning = true
                thread(start = true, isDaemon = true) {
                    while (isAlive()) {
                        try {
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            if (isRunning) println("[MovieKing v37] Accept Error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isRunning = false
            }
        }

        fun updateSession(headers: Map<String, String>, key: ByteArray?): Int {
            currentHeaders = headers
            currentKey = key
            return port
        }

        fun setPlaylist(m3u8: String) {
            currentPlaylist = m3u8
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close() } catch (e: Exception) { }
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
                        
                        if (path.contains("/playlist.m3u8")) {
                            val data = currentPlaylist.toByteArray()
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(data)
                            output.flush()
                        }
                        else if (path.contains("/key.bin") && currentKey != null) {
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${currentKey!!.size}\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(currentKey)
                            output.flush()
                        }
                        else if (path.contains("/proxy")) {
                            val urlParam = path.substringAfter("url=").substringBefore(" ")
                            val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                            
                            try {
                                runBlocking {
                                    // [핵심] 모든 헤더 전송 (CDN 차단 방지)
                                    val res = app.get(targetUrl, headers = currentHeaders)
                                    
                                    if (res.isSuccessful) {
                                        val inputStream = res.body.byteStream()
                                        
                                        // [핵심] XOR 복호화 적용
                                        val buffer = ByteArray(8192)
                                        var bytesRead = inputStream.read(buffer)
                                        
                                        // 첫 버퍼 검사 및 복호화
                                        if (bytesRead > 0 && currentKey != null) {
                                            // 검증: 0x47이 아니면 XOR 수행
                                            // 0x7F ^ 0x38 = 0x47 이므로 무조건 XOR이 맞음
                                            for (i in 0 until bytesRead) {
                                                buffer[i] = (buffer[i].toInt() xor currentKey!![i % currentKey!!.size].toInt()).toByte()
                                            }
                                            
                                            if (buffer[0] == 0x47.toByte()) {
                                                println("[MovieKing v37] XOR Success! Found 0x47.")
                                            }
                                            
                                            val header = "HTTP/1.1 200 OK\r\n" +
                                                    "Content-Type: video/mp2t\r\n" +
                                                    "Connection: close\r\n\r\n"
                                            output.write(header.toByteArray())
                                            output.write(buffer, 0, bytesRead)
                                            
                                            // 나머지 스트리밍 (Rolling XOR)
                                            var totalRead = bytesRead
                                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                                for (i in 0 until bytesRead) {
                                                    // Key Index는 전체 스트림 기준이어야 함
                                                    val keyIdx = (totalRead + i) % currentKey!!.size
                                                    buffer[i] = (buffer[i].toInt() xor currentKey!![keyIdx].toInt()).toByte()
                                                }
                                                output.write(buffer, 0, bytesRead)
                                                totalRead += bytesRead
                                            }
                                            output.flush()
                                        } else {
                                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                        }
                                        inputStream.close()
                                    } else {
                                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                    }
                                }
                            } catch (e: Exception) {
                                println("[MovieKing v37] Stream Error: $e")
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    println("[MovieKing v37] Socket Error: $e")
                }
            }
        }
    }
}
