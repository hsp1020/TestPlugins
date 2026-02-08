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
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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
        println("=== [MovieKing v39] getUrl Start (Multi-Key Strategy) ===")
        
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
                    println("[MovieKing v39] Error: data-m3u8 not found")
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
            println("[MovieKing v39] UA: $standardUA")

            val playlistResponse = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = playlistResponse.text

            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var rawKeyJson: String? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                rawKeyJson = keyResponse.text
                println("[MovieKing v39] Key JSON Captured.")
            }

            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            // JSON 자체를 넘김
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
            println("[MovieKing v39] Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v39] Error: ${e.message}")
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
    
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var rawKeyJson: String? = null
        @Volatile private var currentPlaylist: String = ""
        
        // 캐싱된 성공 키/모드
        @Volatile private var activeKey: ByteArray? = null
        @Volatile private var activeMode: Int = 0 // 1: XOR, 2: AES-ECB

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
                            if (isRunning) println("[MovieKing v39] Accept Error: ${e.message}")
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
            activeKey = null
            activeMode = 0
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
                            // 플레이어에게는 더미 키 전송 (프록시가 이미 복호화하므로)
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
                                        
                                        // 8KB 버퍼링
                                        val buffer = ByteArray(8192)
                                        inputStream.mark(8192)
                                        val bytesRead = inputStream.read(buffer)
                                        inputStream.reset()
                                        
                                        // 키가 아직 결정 안 됐으면 탐색
                                        if (bytesRead > 32 && activeMode == 0 && rawKeyJson != null) {
                                            findWorkingKey(buffer.copyOfRange(0, 32), rawKeyJson!!)
                                        }

                                        val header = "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: video/mp2t\r\n" +
                                                "Connection: close\r\n\r\n"
                                        output.write(header.toByteArray())
                                        
                                        // 복호화 스트리밍
                                        val workBuffer = ByteArray(8192)
                                        var count: Int
                                        while (inputStream.read(workBuffer).also { count = it } != -1) {
                                            if (activeMode > 0 && activeKey != null) {
                                                val decrypted = decryptData(workBuffer, count, activeKey!!, activeMode)
                                                output.write(decrypted, 0, count)
                                            } else {
                                                output.write(workBuffer, 0, count)
                                            }
                                        }
                                        output.flush()
                                        inputStream.close()
                                    } else {
                                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                    }
                                }
                            } catch (e: Exception) {
                                println("[MovieKing v39] Stream Error: $e")
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    println("[MovieKing v39] Socket Error: $e")
                }
            }
        }

        private fun findWorkingKey(sample: ByteArray, jsonText: String) {
            println("[MovieKing v39] Scanning for working key...")
            val candidates = generateCandidateKeys(jsonText)
            
            for ((name, key) in candidates) {
                // Test XOR
                if ((sample[0].toInt() xor key[0].toInt()).toByte() == 0x47.toByte()) {
                    println("[MovieKing v39] SUCCESS: Found XOR Key using $name")
                    activeKey = key
                    activeMode = 1
                    return
                }
                
                // Test AES-ECB
                try {
                    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                    val dec = cipher.doFinal(sample.copyOfRange(0, 16))
                    if (dec[0] == 0x47.toByte()) {
                        println("[MovieKing v39] SUCCESS: Found AES-ECB Key using $name")
                        activeKey = key
                        activeMode = 2
                        return
                    }
                } catch (e: Exception) {}
            }
            println("[MovieKing v39] FAILED to find key.")
        }

        private fun generateCandidateKeys(jsonText: String): List<Pair<String, ByteArray>> {
            val list = mutableListOf<Pair<String, ByteArray>>()
            try {
                val decodedJsonStr = try { String(Base64.decode(jsonText, Base64.DEFAULT)) } catch (e: Exception) { jsonText }
                
                // 파싱
                val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
                val encKeyStr = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return list
                val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
                val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return list
                
                val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
                val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
                val permutation = permString.split(",").map { it.trim().toInt() }

                // Candidate A: String Slice (v35)
                list.add("StringSlice" to assembleKeyBytes(encKeyStr.toByteArray(), permutation))
                
                // Candidate B: Base64 Decode -> Byte Slice (v32)
                try {
                    val decodedBytes = Base64.decode(encKeyStr, Base64.DEFAULT)
                    list.add("Base64Slice" to assembleKeyBytes(decodedBytes, permutation))
                } catch (e: Exception) {}

            } catch (e: Exception) {}
            return list
        }

        private fun assembleKeyBytes(source: ByteArray, perm: List<Int>): ByteArray {
            val segments = mutableListOf<ByteArray>()
            var offset = 0
            // [4, 4, 4, 4] size, 2 noise
            for (i in 0 until 4) {
                if (offset + 4 > source.size) break
                segments.add(source.copyOfRange(offset, offset + 4))
                offset += 4 + 2
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

        private fun decryptData(data: ByteArray, len: Int, key: ByteArray, mode: Int): ByteArray {
            if (mode == 1) { // XOR
                val res = ByteArray(len)
                for (i in 0 until len) {
                    res[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
                }
                return res
            } else if (mode == 2) { // AES
                try {
                    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                    val procLen = (len / 16) * 16
                    if (procLen > 0) {
                        val res = data.copyOf(len)
                        val dec = cipher.doFinal(data, 0, procLen)
                        System.arraycopy(dec, 0, res, 0, procLen)
                        return res
                    }
                } catch (e: Exception) {}
            }
            return data
        }
    }
}
