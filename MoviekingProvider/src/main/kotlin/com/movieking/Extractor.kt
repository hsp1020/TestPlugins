package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * v113: Mega-Range Brute-Force & Smart Logging
 * [수정 사항]
 * 1. 범위 확대: Target ± 5000 (약 10,000개 IV 대입).
 * 2. 스마트 로그: 시작/종료 로그 외에 매 1,000번째마다 진행 상황 출력 (로그 폭주 방지 + 동작 확인).
 * 3. 2-Sync 검증: 0번지와 188번지 동시 확인으로 오탐 방지.
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
        println("=== [MovieKing v113] getUrl Start ===")
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val startSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            println("[MovieKing v113] Playlist Start Sequence: $startSeq")

            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop(); proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val candidates = if (keyMatch != null) solveKeyCandidatesV87(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            
            val seqMap = ConcurrentHashMap<String, Long>()
            val lines = playlistRes.lines()
            val newLines = mutableListOf<String>()
            var currentSeq = startSeq
            
            val proxyRoot = "http://127.0.0.1:${proxyServer!!.port}/$videoId"

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) continue
                
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    seqMap[segmentUrl] = currentSeq
                    newLines.add("$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}")
                    currentSeq++
                } else {
                    newLines.add(line)
                }
            }
            
            val m3u8Content = newLines.joinToString("\n")
            proxyServer!!.updateSession(baseHeaders, keyMatch?.groupValues?.get(2), candidates, m3u8Content, seqMap)
            
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v113] FATAL Error: $e") }
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
        } catch (e: Exception) {}
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
        @Volatile private var seqMap: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        
        fun updateSession(h: Map<String, String>, iv: String?, k: List<ByteArray>, p: String, map: ConcurrentHashMap<String, Long>) {
            currentHeaders = h; playlistIv = iv; keyCandidates = k; currentPlaylist = p; seqMap = map
        }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    output.write(currentPlaylist.toByteArray(charset("UTF-8")))
                } else if (path.contains("/proxy")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    val expectedSeq = seqMap[targetUrl] ?: 0L
                    
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val rawData = res.body.bytes()
                            val headHex = rawData.take(4).joinToString(" ") { "%02X".format(it) }
                            println("[MovieKing v113] TS Received | ExpSeq:$expectedSeq | Header: $headHex")

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                            if (rawData.isNotEmpty() && rawData[0] == 0x47.toByte() && rawData.size > 188 && rawData[188] == 0x47.toByte()) {
                                println("[MovieKing v113] Plain TS Detected. Passing RAW.")
                                output.write(rawData)
                            } else {
                                // [v113] 범위 5000으로 확대
                                val decrypted = bruteForceMega(rawData, expectedSeq)
                                if (decrypted != null) {
                                    output.write(decrypted)
                                } else {
                                    println("[MovieKing v113] All IVs Failed (Mega Scan). Sending RAW.")
                                    output.write(rawData)
                                }
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v113] Proxy Error: $e") }
        }

        private fun bruteForceMega(data: ByteArray, targetSeq: Long): ByteArray? {
            val ivs = mutableListOf<ByteArray>()
            
            // 1. Hex IV
            if (!playlistIv.isNullOrEmpty()) {
                try {
                    val hex = playlistIv!!.removePrefix("0x")
                    val iv = ByteArray(16)
                    hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                    ivs.add(iv)
                } catch(e:Exception) {}
            }
            
            // 2. Zero IV
            ivs.add(ByteArray(16))
            
            // 3. Mega Floating IVs (Target ± 5000)
            val startRange = maxOf(0L, targetSeq - 5000)
            val endRange = targetSeq + 5000
            
            // [스마트 로그] 시작 알림
            println("[MovieKing v113] Starting Mega Scan: [$startRange ~ $endRange] (Plus Zero-Base)")

            for (seq in startRange..endRange) {
                val iv = ByteArray(16)
                for (i in 0..7) iv[15 - i] = (seq shr (i * 8)).toByte()
                ivs.add(iv)
            }

            // 4. Zero-Base Scan (0 ~ 100)
            for (seq in 0L..100L) {
                val iv = ByteArray(16)
                for (i in 0..7) iv[15 - i] = (seq shr (i * 8)).toByte()
                ivs.add(iv)
            }

            val checkSize = 188 * 2
            if (data.size < checkSize) return null

            // [전수 조사]
            for ((keyIdx, key) in keyCandidates.withIndex()) {
                for ((ivIdx, iv) in ivs.withIndex()) {
                    // [스마트 로그] 1000번째마다 생존 신고 (로그 폭주 방지 + 동작 확인)
                    if (ivIdx % 1000 == 0) {
                        println("[MovieKing v113] Scanning... current index: $ivIdx / ${ivs.size}")
                    }

                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val head = cipher.update(data.take(checkSize).toByteArray())
                        
                        // 2-Sync 검증
                        if (head.isNotEmpty() && head[0] == 0x47.toByte() && head.size > 188 && head[188] == 0x47.toByte()) {
                            println("[MovieKing v113] JACKPOT! KeyIdx:$keyIdx, IV_Index:$ivIdx")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            return cipher.doFinal(data)
                        }
                    } catch (e: Exception) {}
                }
            }
            return null
        }
    }
}
