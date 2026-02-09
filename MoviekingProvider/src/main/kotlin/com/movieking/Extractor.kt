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
 * v120: Combinatorial Key Breaker
 * [유저 피드백 반영]
 * 1. 노이즈 패턴 전수 조사: 'Stars and Bars' 알고리즘으로 잉여 바이트를 5개 구역에 분배하는 모든 경우의 수 생성.
 * (예: 앞뒤 제거, 중간 제거, 징검다리 제거 등 모든 패턴 포함)
 * 2. 순서 조합 전수 조사: 4개 조각의 모든 순열(24가지) 적용.
 * 3. 2-Sync 검증 & 교차 검증: 확실한 정답 하나를 찾아냄.
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
        println("=== [MovieKing v120] getUrl Start ===")
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val startSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            println("[MovieKing v120] Playlist Start Sequence: $startSeq")

            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop(); proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            println("[MovieKing v120] Explicit Hex IV: ${hexIv ?: "NONE"}")

            // [v120] 수학적 전수 조사로 키 후보 생성
            val candidates = if (keyMatch != null) solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            proxyServer!!.updateSession(baseHeaders, hexIv, candidates)
            
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
            proxyServer!!.setPlaylist(m3u8Content)
            proxyServer!!.updateSeqMap(seqMap)
            
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v120] FATAL Error: $e") }
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

    // [v120] Combinatorial Key Generation
    private suspend fun solveKeyCandidatesCombinatorial(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            println("[MovieKing v120] Key JSON: $json")
            
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            // rule 정보는 무시하고, 데이터 자체를 분석합니다.
            
            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }
            val raw = encStr.toByteArray()

            // 1. Raw Key Check
            if (b64.size == 16) list.add(b64)

            // 2. Combinatorial Breaker (Stars and Bars)
            // 가정: 키는 16바이트이며, 4바이트 조각 4개로 구성된다.
            // 남은 바이트(slack)를 5개의 간격(gaps)에 배분하는 모든 경우의 수 생성.
            listOf(b64).forEach { src ->
                if (src.size > 16) {
                    val slack = src.size - 16
                    val distributions = generateDistributions(slack, 5) // 5 bins: Pre, Gap1, Gap2, Gap3, Post
                    
                    val allPerms = generatePermutations(listOf(0, 1, 2, 3)) // 4! = 24

                    for (gaps in distributions) {
                        // gaps = [pre, gap1, gap2, gap3, post]
                        // Extract 4 segments based on gaps
                        try {
                            val segs = mutableListOf<ByteArray>()
                            var idx = gaps[0] // pre offset
                            // Seg 1
                            segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[1]
                            // Seg 2
                            segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[2]
                            // Seg 3
                            segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[3]
                            // Seg 4
                            segs.add(src.copyOfRange(idx, idx + 4))

                            // Try all permutations
                            for (perm in allPerms) {
                                val k = ByteArray(16)
                                for (j in 0 until 4) {
                                    System.arraycopy(segs[perm[j]], 0, k, j * 4, 4)
                                }
                                list.add(k)
                            }
                        } catch (e: Exception) { /* Ignore invalid ranges */ }
                    }
                }
            }

            val uniqueList = list.distinctBy { it.contentHashCode() }
            println("[MovieKing v120] Total Combinatorial Keys: ${uniqueList.size}")
            // 디버깅용 로그 (너무 많으므로 앞뒤만 출력)
            if (uniqueList.isNotEmpty()) {
                println("Key #0: ${uniqueList[0].joinToString("") { "%02X".format(it) }}")
                println("... Last Key: ${uniqueList.last().joinToString("") { "%02X".format(it) }}")
            }
            return uniqueList
        } catch (e: Exception) { println("[MovieKing v120] Key Gen Error: $e"); return emptyList() }
    }

    // Stars and Bars 알고리즘: n개의 아이템을 k개의 바구니에 나누어 담는 모든 경우의 수
    private fun generateDistributions(n: Int, k: Int): List<List<Int>> {
        if (k == 1) return listOf(listOf(n))
        val result = mutableListOf<List<Int>>()
        for (i in 0..n) {
            for (sub in generateDistributions(n - i, k - 1)) {
                result.add(listOf(i) + sub)
            }
        }
        return result
    }

    private fun <T> generatePermutations(list: List<T>): List<List<T>> {
        if (list.isEmpty()) return listOf(emptyList())
        val result = mutableListOf<List<T>>()
        for (i in list.indices) {
            val elem = list[i]
            val rest = list.take(i) + list.drop(i + 1)
            for (p in generatePermutations(rest)) {
                result.add(listOf(elem) + p)
            }
        }
        return result
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
        
        fun updateSession(h: Map<String, String>, iv: String?, k: List<ByteArray>) {
            currentHeaders = h; playlistIv = iv; keyCandidates = k
        }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }

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
                    val seq = seqMap[targetUrl] ?: 0L
                    
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val rawData = res.body.bytes()
                            val headHex = rawData.take(4).joinToString(" ") { "%02X".format(it) }
                            println("[MovieKing v120] TS Recv | Seq:$seq | Head:$headHex")

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                            if (rawData.isNotEmpty() && rawData[0] == 0x47.toByte() && rawData.size > 188 && rawData[188] == 0x47.toByte()) {
                                println("[MovieKing v120] Plain TS. Passing.")
                                output.write(rawData)
                            } else {
                                // [v120] Cross-Check with Many Candidates
                                val decrypted = tryDecryptCrossCheck(rawData, seq)
                                if (decrypted != null) {
                                    output.write(decrypted)
                                } else {
                                    println("[MovieKing v120] Cross-Check Failed. Sending RAW.")
                                    output.write(rawData)
                                }
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v120] Proxy Error: $e") }
        }

        private fun tryDecryptCrossCheck(data: ByteArray, seq: Long): ByteArray? {
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
            // 2. Seq IV
            val seqIv = ByteArray(16)
            for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
            ivs.add(seqIv)
            // 3. Zero IV
            ivs.add(ByteArray(16))

            val checkSize = 188 * 2
            if (data.size < checkSize) return null

            // [Cross-Check]
            for ((keyIdx, key) in keyCandidates.withIndex()) {
                for ((ivIdx, iv) in ivs.withIndex()) {
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val head = cipher.update(data.take(checkSize).toByteArray())
                        
                        if (head.isNotEmpty() && head[0] == 0x47.toByte() && head.size > 188 && head[188] == 0x47.toByte()) {
                            println("[MovieKing v120] JACKPOT! Key#$keyIdx, IV#$ivIdx")
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
