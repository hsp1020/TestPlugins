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
        println("=== [MovieKing v45] getUrl Start (Sync Fix + Absolute XOR) ===")
        
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
                    println("[MovieKing v45] Error: data-m3u8 not found")
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
            println("[MovieKing v45] UA: $standardUA")

            val playlistResponse = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = playlistResponse.text

            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var actualKeyBytes: ByteArray? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                
                // [v44 성공 로직] Robust Key Gen
                actualKeyBytes = decryptKeyRobust(keyResponse.text)
                
                if (actualKeyBytes != null) {
                    val keyHex = actualKeyBytes.joinToString("") { "%02X".format(it) }
                    println("[MovieKing v45] Key: $keyHex")
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
            println("[MovieKing v45] Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v45] Error: ${e.message}")
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

    private fun decryptKeyRobust(jsonText: String): ByteArray? {
        return try {
            val decodedJsonStr = try { 
                String(Base64.decode(jsonText, Base64.DEFAULT)) 
            } catch (e: Exception) { jsonText }
            
            val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            var encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            encKeyB64 = encKeyB64.replace("\\/", "/")
            val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
            val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null

            val encryptedBytes = try {
                Base64.decode(encKeyB64, Base64.DEFAULT)
            } catch (e: Exception) {
                Base64.decode(encKeyB64, Base64.NO_WRAP)
            }
            
            val segSizesRegex = """"segment_sizes"\s*:\s*\[([\d,]+)\]""".toRegex()
            val segSizesStr = segSizesRegex.find(ruleJson)?.groupValues?.get(1) ?: "4,4,4,4"
            val segmentSizes = segSizesStr.split(",").map { it.trim().toInt() }
            
            val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
            val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
            val permutation = permString.split(",").map { it.trim().toInt() }

            val cleanSegments = mutableListOf<ByteArray>()
            var currentOffset = 0
            val noiseLen = 2
            
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
                            if (isRunning) println("[MovieKing v45] Accept Error: ${e.message}")
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
                            val dummy = ByteArray(16)
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(dummy)
                            output.flush()
                        }
                        else if (path.contains("/proxy")) {
                            val urlParam = path.substringAfter("url=").substringBefore(" ")
                            val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                            
                            try {
                                runBlocking {
                                    val res = app.get(targetUrl, headers = currentHeaders)
                                    
                                    if (res.isSuccessful) {
                                        val inputStream = BufferedInputStream(res.body.byteStream())
                                        
                                        // 8KB 버퍼링 (헤더 탐색용)
                                        val buffer = ByteArray(8192)
                                        var bytesRead = inputStream.read(buffer)
                                        var globalFileOffset = 0L // [핵심] 절대 파일 오프셋 추적
                                        
                                        if (bytesRead > 0 && currentKey != null) {
                                            // 1. XOR 복호화 (첫 블록)
                                            val decryptedBuffer = buffer.clone()
                                            for (i in 0 until bytesRead) {
                                                // 키 인덱스는 파일의 절대 위치(globalFileOffset + i) 기준
                                                decryptedBuffer[i] = (buffer[i].toInt() xor currentKey!![(i % currentKey!!.size)].toInt()).toByte()
                                            }
                                            
                                            // 2. 0x47 정밀 탐색 (3-Packet Check)
                                            var foundOffset = -1
                                            for (i in 0 until bytesRead) {
                                                if (decryptedBuffer[i] == 0x47.toByte()) {
                                                    // [강화된 검증] 188바이트 뒤도 0x47인가? (TS 패킷 사이즈)
                                                    var isSync = true
                                                    if (i + 188 < bytesRead) {
                                                        if (decryptedBuffer[i+188] != 0x47.toByte()) isSync = false
                                                    }
                                                    if (i + 376 < bytesRead) {
                                                        if (decryptedBuffer[i+376] != 0x47.toByte()) isSync = false
                                                    }
                                                    
                                                    if (isSync) {
                                                        foundOffset = i
                                                        break
                                                    }
                                                }
                                            }
                                            
                                            if (foundOffset != -1) {
                                                println("[MovieKing v45] Valid Sync Byte found at $foundOffset")
                                                val header = "HTTP/1.1 200 OK\r\n" +
                                                        "Content-Type: video/mp2t\r\n" +
                                                        "Connection: close\r\n\r\n"
                                                output.write(header.toByteArray())
                                                
                                                // 찾은 위치부터 전송
                                                output.write(decryptedBuffer, foundOffset, bytesRead - foundOffset)
                                                
                                                // [중요] globalOffset 업데이트 (이미 읽은 양만큼)
                                                globalFileOffset += bytesRead
                                                
                                                // 3. 나머지 스트리밍 (절대 위치 유지)
                                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                                    for (i in 0 until bytesRead) {
                                                        // 현재 처리하는 바이트의 절대 위치: globalFileOffset + i
                                                        val keyIdx = ((globalFileOffset + i) % currentKey!!.size).toInt()
                                                        buffer[i] = (buffer[i].toInt() xor currentKey!![keyIdx].toInt()).toByte()
                                                    }
                                                    output.write(buffer, 0, bytesRead)
                                                    globalFileOffset += bytesRead // 오프셋 누적
                                                }
                                                output.flush()
                                            } else {
                                                println("[MovieKing v45] Sync Byte NOT found (Strict Check).")
                                                output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                            }
                                        } else {
                                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                        }
                                        inputStream.close()
                                    } else {
                                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                    }
                                }
                            } catch (e: Exception) {
                                println("[MovieKing v45] Stream Error: $e")
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    println("[MovieKing v45] Socket Error: $e")
                }
            }
        }
    }
}
