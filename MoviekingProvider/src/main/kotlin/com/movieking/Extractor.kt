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
 * v106: Full-Trace Simple Switching Engine
 * [유저 지침 준수]
 * 1. 무조건 로그: 데이터 수신, 헤더 검사, 분기 판단, 복호화 결과 등 모든 과정 println 출력.
 * 2. 단순화 로직: 복잡한 가설 제거. (0x47이면 통과, 아니면 복호화)
 * 3. 디버깅 최적화: 로그만 보면 왜 재생되었는지/실패했는지 1초 만에 파악 가능하도록 설계.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v106] getUrl Start ===")
        try {
            val videoId = extractVideoIdDeep(url)
            println("[MovieKing v106] Target ID: $videoId")

            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop(); proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            // v87 표준 방식 키 생성
            val candidates = if (keyMatch != null) solveKeyCandidatesV87(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            proxyServer!!.updateSession(baseHeaders, keyMatch?.groupValues?.get(2), candidates)
            
            val port = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$port/$videoId"
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v106] FATAL Error: $e") }
    }

    private fun extractVideoIdDeep(url: String): String {
        try {
            val token = url.split("/v1/").getOrNull(1)?.split(".")?.getOrNull(1)
            if (token != null) {
                val decoded = String(Base64.decode(token, Base64.URL_SAFE))
                return Regex(""""id"\s*:\s*(\d+)""").find(decoded)?.groupValues?.get(1) ?: "ID_ERR"
            }
        } catch (e: Exception) {}
        return "ID_ERR"
    }

    private suspend fun solveKeyCandidatesV87(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            // [로그] 키 규칙 원문 확인
            println("[MovieKing v106] Key JSON Rule: $json")
            
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val rule = Regex(""""rule"\s*:\s*(\{.*?\})""").find(json)?.groupValues?.get(1) ?: ""
            val noise = Regex(""""noise_length"\s*:\s*(\d+)""").find(rule)?.groupValues?.get(1)?.toInt() ?: 2
            val size = Regex(""""segment_sizes"\s*:\s*\[(\d+)""").find(rule)?.groupValues?.get(1)?.toInt() ?: 4
            val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(rule)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)

            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }
            val raw = encStr.toByteArray()

            listOf(b64, raw).forEach { src ->
                val segments = mutableListOf<ByteArray>()
                var offset = 0
                for (i in 0 until 4) {
                    if (offset + size <= src.size) {
                        segments.add(src.copyOfRange(offset, offset + size))
                        offset += (size + noise)
                    }
                }
                if (segments.size == 4) {
                    val k = ByteArray(16)
                    for (j in 0 until 4) System.arraycopy(segments[perm[j]], 0, k, j * 4, 4)
                    list.add(k)
                }
            }
            println("[MovieKing v106] Generated Key Candidates: ${list.size}")
        } catch (e: Exception) { println("[MovieKing v106] Key Build Error: $e") }
        return list.distinctBy { it.contentHashCode() }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var keyCandidates: List<ByteArray> = emptyList()

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>, iv: String?, k: List<ByteArray>) {
            currentHeaders = h; playlistIv = iv; keyCandidates = k
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
                } else if (path.contains("/proxy")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    val seqStr = Regex("""(\d+)\.ts""").find(targetUrl)?.groupValues?.get(1) ?: "?"
                    
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val rawData = res.body.bytes()
                            // [로그] 수신 데이터 헤더 (암호화 여부 판단 기준)
                            val headHex = rawData.take(16).joinToString(" ") { "%02X".format(it) }
                            println("[MovieKing v106] TS#$seqStr | Size: ${rawData.size} | Header: $headHex")

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                            // [로직] 0x47 체크 -> 분기 처리
                            if (rawData.isNotEmpty() && rawData[0] == 0x47.toByte()) {
                                println("[MovieKing v106] TS#$seqStr -> [Action] Plain TS Detected. Sending RAW.")
                                output.write(rawData)
                            } else {
                                println("[MovieKing v106] TS#$seqStr -> [Action] Encrypted/Garbage. Trying Decrypt.")
                                val decrypted = tryDecrypt(rawData, seqStr)
                                if (decrypted != null) {
                                    output.write(decrypted)
                                } else {
                                    println("[MovieKing v106] TS#$seqStr -> [Fail] Decryption Failed. Sending RAW (Fallback).")
                                    output.write(rawData)
                                }
                            }
                        } else {
                            println("[MovieKing v106] Download Failed: ${res.code}")
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v106] Proxy Error: $e") }
        }

        private fun tryDecrypt(data: ByteArray, seqStr: String): ByteArray? {
            // 모든 키와 단순 IV(Hex, Zero)로만 시도
            for ((idx, key) in keyCandidates.withIndex()) {
                for (mode in 0..1) { // 0: Hex IV, 1: Zero IV
                    try {
                        val iv = getIv(mode)
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        
                        // 16바이트만 먼저 복호화해서 0x47 확인 (속도 최적화)
                        val head = cipher.update(data.take(16).toByteArray())
                        if (head.isNotEmpty() && head[0] == 0x47.toByte()) {
                            // [로그] 성공한 키 정보 기록
                            println("[MovieKing v106] TS#$seqStr -> [Success] Decrypted! KeyIdx:$idx, Mode:$mode")
                            
                            // 전체 복호화 수행
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            return cipher.doFinal(data)
                        }
                    } catch (e: Exception) {}
                }
            }
            return null
        }

        private fun getIv(mode: Int): ByteArray {
            val iv = ByteArray(16)
            if (mode == 0) {
                val hex = playlistIv?.removePrefix("0x") ?: ""
                try { hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() } } catch(e:Exception) {}
            }
            return iv
        }
    }
}
