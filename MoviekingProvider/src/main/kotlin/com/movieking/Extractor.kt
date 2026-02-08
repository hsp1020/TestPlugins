package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v70] getUrl Start (Standard AES Restore) ===")
        try {
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            // [중요] 키 추출을 위한 원본 URL 정보 저장
            val keyUriMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""").find(playlistRes)
            val realKeyUrl = keyUriMatch?.groupValues?.get(1)
            
            val port = proxyServer!!.updateSession(baseHeaders, realKeyUrl)
            val proxyBaseUrl = "http://127.0.0.1:$port"
            
            // [핵심] AES 태그를 유지하되, URI만 로컬 프록시로 변경
            var m3u8Content = if (realKeyUrl != null) {
                playlistRes.replace(realKeyUrl, "$proxyBaseUrl/key.bin")
            } else playlistRes

            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyBaseUrl/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "$proxyBaseUrl/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/"
            })
        } catch (e: Exception) { println("[MovieKing v70] getUrl Error: $e") }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var targetKeyUrl: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var binaryKeyCache: ByteArray? = null

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>, kUrl: String?) = port.also { 
            currentHeaders = h; targetKeyUrl = kUrl; binaryKeyCache = null 
        }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray() + currentPlaylist.toByteArray())
                } 
                else if (path.contains("/key.bin")) {
                    // [핵심] JSON을 16바이트 이진 키로 변환하여 전송
                    val keyData = getOrFetchBinaryKey()
                    if (keyData != null) {
                        println("[MovieKing v70] Delivering 16-byte Binary Key to Player.")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\n\r\n".toByteArray())
                        output.write(keyData)
                    } else {
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                else if (path.contains("/proxy")) {
                    val url = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    runBlocking {
                        val res = app.get(url, headers = currentHeaders)
                        if (res.isSuccessful) {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            val stream = res.body.byteStream()
                            val buffer = ByteArray(8192)
                            var count: Int
                            while (stream.read(buffer).also { count = it } != -1) {
                                // [중요] 암호화된 상태 그대로 전달 (ExoPlayer가 AES 복호화 수행)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v70] Proxy Error: $e") }
        }

        private fun getOrFetchBinaryKey(): ByteArray? {
            if (binaryKeyCache != null) return binaryKeyCache
            val url = targetKeyUrl ?: return null
            return try {
                runBlocking {
                    val res = app.get(url, headers = currentHeaders).text
                    val key = decryptKeyRobust(res)
                    binaryKeyCache = key
                    key
                }
            } catch (e: Exception) { null }
        }

        private fun decryptKeyRobust(jsonText: String): ByteArray? {
            return try {
                val decodedJsonStr = try { String(Base64.decode(jsonText, Base64.DEFAULT)) } catch (e: Exception) { jsonText }
                val encKeyB64 = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decodedJsonStr)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
                val ruleJson = Regex(""""rule"\s*:\s*(\{.*?\})""").find(decodedJsonStr)?.groupValues?.get(1) ?: return null
                
                val segSizes = Regex(""""segment_sizes"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(4,4,4,4)
                val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)
                
                val segments = mutableListOf<String>()
                var cur = 0
                for (s in segSizes) {
                    segments.add(encKeyB64.substring(cur, cur + s))
                    cur += s + 2 // noise
                }
                
                val finalKeyStr = StringBuilder()
                for (idx in perm) finalKeyStr.append(segments[idx])
                
                finalKeyStr.toString().toByteArray(Charsets.UTF_8)
            } catch (e: Exception) { null }
        }
    }
}
