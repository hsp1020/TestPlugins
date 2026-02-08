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
        println("=== [MovieKing v42] getUrl Start (XOR Scanner) ===")
        
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
                    println("[MovieKing v42] Error: data-m3u8 not found")
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
            println("[MovieKing v42] UA: $standardUA")

            val playlistResponse = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = playlistResponse.text

            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var rawKeyJson: String? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                rawKeyJson = keyResponse.text
                println("[MovieKing v42] Key JSON Captured.")
            }

            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            val port = proxyServer!!.updateSession(baseHeaders, rawKeyJson)
            val proxyBaseUrl = "http://127.0.0.1:$port"

            if (keyMatch != null) {
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
            println("[MovieKing v42] Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v42] Error: ${e.message}")
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
    
    // --- Proxy Web Server (XOR Scanner) ---
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var rawKeyJson: String? = null
        @Volatile private var currentPlaylist: String = ""
        
        // 캐싱
        @Volatile private var cachedKey: ByteArray? = null
        @Volatile private var cachedOffset: Int = -1

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
                            if (isRunning) println("[MovieKing v42] Accept Error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isRunning = false
            }
        }

        fun updateSession(headers: Map<String, String>, json: String?): Int {
            currentHeaders = headers
            rawKeyJson = json
            cachedKey = null
            cachedOffset = -1
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
                        else if (path.contains("/key.bin")) {
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
                                        inputStream.mark(8192)
                                        val bytesRead = inputStream.read(buffer)
                                        inputStream.reset()
                                        
                                        var foundKey: ByteArray? = null
                                        var foundOffset = -1
                                        
                                        if (bytesRead > 0 && rawKeyJson != null) {
                                            // 키 후보 생성
                                            val keys = generateCandidateKeys(rawKeyJson!!)
                                            
                                            // [핵심] 키별로 XOR 해보면서 0x47 찾기
                                            scanLoop@ for (key in keys) {
                                                // 영상 앞부분 2KB 정도만 스캔
                                                val limit = minOf(bytesRead, 2048)
                                                for (i in 0 until limit) {
                                                    // XOR 복호화 시도
                                                    val decryptedByte = (buffer[i].toInt() xor key[i % key.size].toInt()).toByte()
                                                    
                                                    // Sync Byte 발견?
                                                    if (decryptedByte == 0x47.toByte()) {
                                                        // 추가 검증 (188바이트 뒤도 0x47인지)
                                                        if (i + 188 < bytesRead) {
                                                            val nextByte = (buffer[i + 188].toInt() xor key[(i + 188) % key.size].toInt()).toByte()
                                                            if (nextByte == 0x47.toByte()) {
                                                                println("[MovieKing v42] Match! Offset: $i, Key(Hex): ${key.joinToString(""){"%02X".format(it)}}")
                                                                foundKey = key
                                                                foundOffset = i
                                                                break@scanLoop
                                                            }
                                                        } else {
                                                            // 데이터가 짧으면 그냥 첫 발견을 믿음
                                                            println("[MovieKing v42] Match (Short)! Offset: $i")
                                                            foundKey = key
                                                            foundOffset = i
                                                            break@scanLoop
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (foundKey != null && foundOffset != -1) {
                                            val header = "HTTP/1.1 200 OK\r\n" +
                                                    "Content-Type: video/mp2t\r\n" +
                                                    "Connection: close\r\n\r\n"
                                            output.write(header.toByteArray())
                                            
                                            // 쓰레기 건너뛰기
                                            inputStream.skip(foundOffset.toLong())
                                            
                                            // 스트리밍 + XOR 복호화
                                            // 주의: 건너뛰었으므로 XOR 키 인덱스는 0부터 시작하는 게 아니라 foundOffset부터 시작해야 함?
                                            // 아니면 서버가 쓰레기 포함 전체를 XOR 했나?
                                            // -> 보통 전체 파일 XOR임. 따라서 Key Index는 (TotalRead + foundOffset) % KeyLen
                                            
                                            var count: Int
                                            var totalRead = foundOffset // 파일의 절대 위치
                                            
                                            while (inputStream.read(buffer).also { count = it } != -1) {
                                                for (i in 0 until count) {
                                                    val keyIdx = (totalRead + i) % foundKey!!.size
                                                    buffer[i] = (buffer[i].toInt() xor foundKey!![keyIdx].toInt()).toByte()
                                                }
                                                output.write(buffer, 0, count)
                                                totalRead += count
                                            }
                                            output.flush()
                                        } else {
                                            println("[MovieKing v42] Scan Failed. No Sync Byte found.")
                                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                        }
                                        inputStream.close()
                                    } else {
                                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                    }
                                }
                            } catch (e: Exception) {
                                println("[MovieKing v42] Stream Error: $e")
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    println("[MovieKing v42] Socket Error: $e")
                }
            }
        }

        private fun generateCandidateKeys(jsonText: String): List<ByteArray> {
            val list = mutableListOf<ByteArray>()
            try {
                val decodedJsonStr = try { String(Base64.decode(jsonText, Base64.DEFAULT)) } catch (e: Exception) { jsonText }
                
                val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
                val encKeyStr = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return list
                val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
                val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return list
                
                val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
                val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
                val permutation = permString.split(",").map { it.trim().toInt() }

                // Candidate A: String Slice (Most likely)
                list.add(assembleKeyBytes(encKeyStr.toByteArray(), permutation))
                
                // Candidate B: Base64 Decode Slice
                try {
                    val decodedBytes = Base64.decode(encKeyStr, Base64.DEFAULT)
                    list.add(assembleKeyBytes(decodedBytes, permutation))
                } catch (e: Exception) {}

            } catch (e: Exception) {}
            return list
        }

        private fun assembleKeyBytes(source: ByteArray, perm: List<Int>): ByteArray {
            val segments = mutableListOf<ByteArray>()
            var offset = 0
            for (i in 0 until 4) {
                if (offset + 4 > source.size) break
                segments.add(source.copyOfRange(offset, offset + 4))
                offset += 4 + 2 // 4 Data + 2 Noise
            }
            
            val finalKey = ByteArray(16)
            var finalOffset = 0
            for (idx in perm) {
                if (idx < segments.size) {
                    System.arraycopy(segments[idx], 0, finalKey, finalOffset, 4)
                    finalOffset += 4
                }
            }
            return finalKey
        }
    }
}
