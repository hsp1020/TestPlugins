package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * v78: Universal IV Detection Mode
 * [수정 내역]
 * 1. Sequence Number IV 자동 추출 및 시도 추가
 * 2. JSON Rule(Noise, Size) 동적 적용 (하드코딩 제거)
 * 3. IV 파싱 안정성 강화 및 3단계 교차 검증
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v78] getUrl Start (Universal Mode) ===")
        try {
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val realKeyUrl = keyMatch?.groupValues?.get(1)
            val tagIv = keyMatch?.groupValues?.get(2)

            val port = proxyServer!!.updateSession(baseHeaders, realKeyUrl, tagIv)
            val proxyBaseUrl = "http://127.0.0.1:$port"
            
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")

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
        } catch (e: Exception) { println("[MovieKing v78] Error: $e") }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var targetKeyUrl: String? = null
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var solvedKey: ByteArray? = null

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>, kUrl: String?, iv: String?) = port.also { 
            currentHeaders = h; targetKeyUrl = kUrl; playlistIv = iv; solvedKey = null 
        }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray() + currentPlaylist.toByteArray())
                } 
                else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            if (solvedKey == null) solvedKey = fetchAndSolveKey()
                            
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            val rawData = res.body.bytes()
                            
                            if (solvedKey != null) {
                                // [업그레이드] Sequence Number IV 추출 (파일명에서 숫자만 추출)
                                val seqNum = Regex("""(\d+)\.ts""").find(targetUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                                val decrypted = decryptAes128Universal(rawData, solvedKey!!, playlistIv, seqNum)
                                output.write(decrypted)
                            } else {
                                output.write(rawData)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v78] Proxy Error: $e") }
        }

        private suspend fun fetchAndSolveKey(): ByteArray? {
            val url = targetKeyUrl ?: return null
            return try {
                val jsonStr = app.get(url, headers = currentHeaders).text
                val decodedJson = if (jsonStr.startsWith("{")) jsonStr else String(Base64.decode(jsonStr, Base64.DEFAULT))
                val encKeyStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decodedJson)?.groupValues?.get(1) ?: return null
                val ruleJson = Regex(""""rule"\s*:\s*(\{.*?\})""").find(decodedJson)?.groupValues?.get(1) ?: return null
                
                // [동적 파싱] 하드코딩 제거
                val noise = Regex(""""noise_length"\s*:\s*(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 2
                val size = Regex(""""segment_sizes"\s*:\s*\[(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 4
                val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)
                
                val rawBytes = Base64.decode(encKeyStr, Base64.DEFAULT)
                val segments = mutableListOf<ByteArray>()
                
                for (i in 0 until 4) {
                    val start = i * (size + noise)
                    if (start + size <= rawBytes.size) {
                        segments.add(rawBytes.copyOfRange(start, start + size))
                    }
                }
                
                if (segments.size < 4) return null
                val finalKey = ByteArray(16)
                for (i in 0 until 4) System.arraycopy(segments[perm[i]], 0, finalKey, i * 4, 4)
                println("[MovieKing v78] Solved Key: ${finalKey.joinToString(""){"%02X".format(it)}}")
                finalKey
            } catch (e: Exception) { null }
        }

        private fun decryptAes128Universal(data: ByteArray, key: ByteArray, ivHex: String?, seq: Long): ByteArray {
            // 1. Tag IV 시도
            if (!ivHex.isNullOrBlank()) {
                try {
                    val cleanIv = if (ivHex.startsWith("0x")) ivHex.substring(2) else ivHex
                    val iv = cleanIv.chunked(2).take(16).map { it.toInt(16).toByte() }.toByteArray()
                    if (iv.size == 16) {
                        val res = runDecrypt(data, key, iv)
                        if (res.isNotEmpty() && res[0] == 0x47.toByte()) return res
                    }
                } catch (e: Exception) {}
            }

            // 2. Media Sequence IV 시도 (000...00[SeqNum])
            try {
                val seqIv = ByteArray(16)
                for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
                val res = runDecrypt(data, key, seqIv)
                if (res.isNotEmpty() && res[0] == 0x47.toByte()) return res
            } catch (e: Exception) {}

            // 3. Zero IV 시도
            try {
                val res = runDecrypt(data, key, ByteArray(16))
                if (res.isNotEmpty() && res[0] == 0x47.toByte()) return res
            } catch (e: Exception) {}

            return data
        }

        private fun runDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            return try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { ByteArray(0) }
        }
    }
}
