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
        println("=== [MovieKing v56] getUrl Start (Extreme Logging) ===")
        
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
                    println("[MovieKing v56] Error: data-m3u8 not found")
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
            println("[MovieKing v56] UA: $standardUA")

            val playlistResponse = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = playlistResponse.text

            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var rawKeyJson: String? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                rawKeyJson = keyResponse.text
                
                // [핵심] 원본 JSON 로그 출력
                println("[MovieKing v56] RAW JSON: $rawKeyJson")
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
            println("[MovieKing v56] Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v56] Error: ${e.message}")
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
                            if (isRunning) println("[MovieKing v56] Accept Error: ${e.message}")
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
                                        
                                        // 32KB Buffer
                                        val buffer = ByteArray(32768)
                                        inputStream.mark(32768)
                                        val bytesRead = inputStream.read(buffer)
                                        inputStream.reset()
                                        
                                        var finalKey: ByteArray? = cachedKey
                                        var finalOffset = cachedOffset
                                        
                                        if (bytesRead > 0 && finalKey == null && rawKeyJson != null) {
                                            // [진단] 키 파싱 상세 로그
                                            val candidates = generateAllKeysWithLog(rawKeyJson!!)
                                            
                                            for ((name, key) in candidates) {
                                                val dec = buffer.clone()
                                                for (i in 0 until bytesRead) {
                                                    dec[i] = (buffer[i].toInt() xor key[i % key.size].toInt()).toByte()
                                                }
                                                
                                                // Triple Check
                                                for (i in 0 until bytesRead - 376) {
                                                    if (dec[i] == 0x47.toByte() && 
                                                        dec[i + 188] == 0x47.toByte() &&
                                                        dec[i + 376] == 0x47.toByte()) {
                                                        println("[MovieKing v56] MATCH! $name at $i")
                                                        finalKey = key
                                                        finalOffset = i
                                                        cachedKey = key
                                                        cachedOffset = i
                                                        break
                                                    }
                                                }
                                                if (finalKey != null) break
                                            }
                                        }

                                        val header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n"
                                        output.write(header.toByteArray())
                                        
                                        if (finalKey != null) {
                                            val decryptedBuffer = buffer.clone()
                                            for (i in 0 until bytesRead) {
                                                decryptedBuffer[i] = (buffer[i].toInt() xor finalKey!![i % finalKey!!.size].toInt()).toByte()
                                            }
                                            
                                            if (bytesRead > finalOffset) {
                                                output.write(decryptedBuffer, finalOffset, bytesRead - finalOffset)
                                            }
                                            
                                            var globalFileOffset = bytesRead.toLong()
                                            var count: Int
                                            
                                            while (inputStream.read(buffer).also { count = it } != -1) {
                                                for (i in 0 until count) {
                                                    val keyIdx = ((globalFileOffset + i) % finalKey!!.size).toInt()
                                                    buffer[i] = (buffer[i].toInt() xor finalKey!![keyIdx].toInt()).toByte()
                                                }
                                                output.write(buffer, 0, count)
                                                globalFileOffset += count
                                            }
                                        } else {
                                            println("[MovieKing v56] All candidates failed. RAW Fallback.")
                                            var count: Int
                                            while (inputStream.read(buffer).also { count = it } != -1) {
                                                output.write(buffer, 0, count)
                                            }
                                        }
                                        output.flush()
                                        inputStream.close()
                                    } else {
                                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                    }
                                }
                            } catch (e: Exception) {
                                println("[MovieKing v56] Stream Error: $e")
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    println("[MovieKing v56] Socket Error: $e")
                }
            }
        }

        private fun generateAllKeysWithLog(jsonText: String): List<Pair<String, ByteArray>> {
            val list = mutableListOf<Pair<String, ByteArray>>()
            try {
                // 1. JSON Decode
                val decodedJsonStr = try { String(Base64.decode(jsonText, Base64.DEFAULT)) } catch (e: Exception) { jsonText }
                println("[MovieKing v56] Decoded JSON: $decodedJsonStr")
                
                val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
                var encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return list
                println("[MovieKing v56] Extracted EncKey: $encKeyB64")
                
                val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
                val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return list
                println("[MovieKing v56] Rule JSON: $ruleJson")
                
                val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
                val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
                val permutation = permString.split(",").map { it.trim().toInt() }
                println("[MovieKing v56] Permutation: $permutation")

                val segSizesRegex = """"segment_sizes"\s*:\s*\[([\d,]+)\]""".toRegex()
                val segSizesStr = segSizesRegex.find(ruleJson)?.groupValues?.get(1) ?: "4,4,4,4"
                val segmentSizes = segSizesStr.split(",").map { it.trim().toInt() }
                println("[MovieKing v56] Segment Sizes: $segmentSizes")

                // Candidate 1: Raw String (No Replace)
                val k1 = assembleWithLog(encKeyB64.toByteArray(), permutation, segmentSizes, "RawString")
                list.add("RawString" to k1)
                
                // Candidate 2: Replaced String
                val k2 = assembleWithLog(encKeyB64.replace("\\/", "/").toByteArray(), permutation, segmentSizes, "ReplacedString")
                list.add("ReplacedString" to k2)
                
                // Candidate 3: Base64
                try {
                    val b64 = Base64.decode(encKeyB64.replace("\\/", "/"), Base64.DEFAULT)
                    val k3 = assembleWithLog(b64, permutation, segmentSizes, "Base64")
                    list.add("Base64" to k3)
                } catch(e:Exception){}

            } catch (e: Exception) {
                println("[MovieKing v56] Key Gen Error: $e")
            }
            return list
        }
        
        private fun assembleWithLog(src: ByteArray, perm: List<Int>, sizes: List<Int>, label: String): ByteArray {
            val segs = mutableListOf<ByteArray>()
            var off = 0
            val noise = 2
            
            for(size in sizes) {
                if(off + size > src.size) break
                segs.add(src.copyOfRange(off, off + size))
                off += size + noise
            }
            
            val res = ByteArray(16)
            var roff = 0
            for(idx in perm) {
                if (idx < segs.size) {
                    val s = segs[idx]
                    System.arraycopy(s, 0, res, roff, s.size)
                    roff += s.size
                }
            }
            val hex = res.joinToString("") { "%02X".format(it) }
            println("[MovieKing v56] $label Key: $hex")
            return res
        }
    }
}
