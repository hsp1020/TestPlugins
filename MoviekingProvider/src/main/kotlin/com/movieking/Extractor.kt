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
 * v76: Proxy-Side AES Decryption Mode
 * [수정 내역]
 * 1. v75의 키 조립 로직(Binary Reconstruction) 확정 유지
 * 2. ExoPlayer 대신 프록시가 직접 AES 복호화 수행 (IV 함정 우회)
 * 3. 3001 에러 원천 봉쇄 (플레이어는 평문 TS만 수신)
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v76] getUrl Start (Proxy AES Mode) ===")
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
            
            // 키 URL 및 IV 추출
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val realKeyUrl = keyMatch?.groupValues?.get(1)
            val tagIv = keyMatch?.groupValues?.get(2)

            val port = proxyServer!!.updateSession(baseHeaders, realKeyUrl, tagIv)
            val proxyBaseUrl = "http://127.0.0.1:$port"
            
            // [핵심] AES 태그를 제거하여 플레이어가 중복 복호화(3001 에러)를 하지 않도록 차단
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
        } catch (e: Exception) { println("[MovieKing v76] Error: $e") }
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
                    val url = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    runBlocking {
                        val res = app.get(url, headers = currentHeaders)
                        if (res.isSuccessful) {
                            if (solvedKey == null) solvedKey = fetchAndSolveKey()
                            
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            
                            val rawData = res.body.bytes()
                            if (solvedKey != null) {
                                // 프록시가 직접 AES 복호화 수행
                                val decrypted = decryptAes128(rawData, solvedKey!!, playlistIv)
                                output.write(decrypted)
                            } else {
                                output.write(rawData)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v76] Proxy Error: $e") }
        }

        private suspend fun fetchAndSolveKey(): ByteArray? {
            val url = targetKeyUrl ?: return null
            return try {
                val jsonStr = app.get(url, headers = currentHeaders).text
                val decodedJson = if (jsonStr.startsWith("{")) jsonStr else String(Base64.decode(jsonStr, Base64.DEFAULT))
                val encKeyStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decodedJson)?.groupValues?.get(1) ?: return null
                val ruleJson = Regex(""""rule"\s*:\s*(\{.*?\})""").find(decodedJson)?.groupValues?.get(1) ?: return null
                val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)
                
                val rawBytes = Base64.decode(encKeyStr, Base64.DEFAULT)
                val segments = mutableListOf<ByteArray>()
                for (i in 0 until 4) {
                    val start = i * 6
                    if (start + 4 <= rawBytes.size) segments.add(rawBytes.copyOfRange(start, start + 4))
                }
                
                val finalKey = ByteArray(16)
                for (i in 0 until 4) System.arraycopy(segments[perm[i]], 0, finalKey, i * 4, 4)
                println("[MovieKing v76] Reconstructed AES Key: ${finalKey.joinToString(""){"%02X".format(it)}}")
                finalKey
            } catch (e: Exception) { null }
        }

        private fun decryptAes128(data: ByteArray, key: ByteArray, ivHex: String?): ByteArray {
            return try {
                // 1. IV 설정 (태그 IV 또는 Zero IV)
                val ivBytes = if (ivHex != null && ivHex.length >= 32) {
                    ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } else ByteArray(16)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))
                
                val decrypted = cipher.doFinal(data)
                
                // 디버그: 복호화 성공 여부 확인
                if (decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                    println("[MovieKing v76] Manual Decryption SUCCESS (0x47 Found)")
                }
                decrypted
            } catch (e: Exception) { 
                // PKCS5Padding 실패 시 NoPadding으로 재시도 (HLS의 특성)
                try {
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    val ivBytes = if (ivHex != null && ivHex.length >= 32) {
                        ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    } else ByteArray(16)
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))
                    cipher.doFinal(data)
                } catch (e2: Exception) { data }
            }
        }
    }
}
