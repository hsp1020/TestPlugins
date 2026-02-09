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
 * v116: Key Strategy Dualism
 * [유저 피드백 반영]
 * 1. IV 스캔 폐기: ±5000 대입은 무의미했음(키가 틀렸으므로). 제거하여 속도 향상.
 * 2. 키 전략 다각화: '가공된 키(v87)' 외에 '원본 키(Raw)'를 후보군에 추가.
 * 3. 단순화: M3U8에 명시된 Hex IV가 있으면 그걸 최우선으로, 없으면 Sequence IV 사용.
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
        println("=== [MovieKing v116] getUrl Start ===")
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val startSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            println("[MovieKing v116] Playlist Start Sequence: $startSeq")

            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop(); proxyServer = ProxyWebServer().apply { start() }
            }
            
            // [중요] M3U8 명시 IV 파싱
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            println("[MovieKing v116] Explicit Hex IV: ${hexIv ?: "NONE"}")

            // [핵심] 키 후보 생성 (가공 키 + 원본 키)
            val candidates = if (keyMatch != null) solveKeyCandidatesDual(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            proxyServer!!.updateSession(baseHeaders, hexIv, candidates)
            
            // URL 매핑 (v109 방식 유지 - 시퀀스 번호 파악용)
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
        } catch (e: Exception) { println("[MovieKing v116] FATAL Error: $e") }
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

    private suspend fun solveKeyCandidatesDual(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            println("[MovieKing v116] Key JSON: $json")
            
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            
            // 1. [Strategy A] 기존 복잡한 로직 (성공 케이스용)
            val rule = Regex(""""rule"\s*:\s*(\{.*?\})""").find(json)?.groupValues?.get(1) ?: ""
            val noise = Regex(""""noise_length"\s*:\s*(\d+)""").find(rule)?.groupValues?.get(1)?.toInt() ?: 2
            val size = Regex(""""segment_sizes"\s*:\s*\[(\d+)""").find(rule)?.groupValues?.get(1)?.toInt() ?: 4
            val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(rule)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)

            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }
            val raw = encStr.toByteArray() // String bytes

            // 가공 로직 적용
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

            // 2. [Strategy B] 원본 키 (실패 케이스 해결용)
            // 암호화된 키 문자열 자체가 Base64로 인코딩된 '진짜 키'일 가능성
            if (b64.size == 16) {
                list.add(b64)
                println("[MovieKing v116] Added Raw Base64 Key")
            }

            // 로그로 키 확인
            val uniqueList = list.distinctBy { it.contentHashCode() }
            uniqueList.forEachIndexed { i, k ->
                println("[MovieKing v116] Candidate Key #$i: ${k.joinToString("") { "%02X".format(it) }}")
            }
            return uniqueList
        } catch (e: Exception) { println("[MovieKing v116] Key Build Error: $e"); return emptyList() }
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
                            // 헤더 로그
                            val headHex = rawData.take(4).joinToString(" ") { "%02X".format(it) }
                            println("[MovieKing v116] TS Recv | Seq:$seq | Head:$headHex")

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                            if (rawData.isNotEmpty() && rawData[0] == 0x47.toByte()) {
                                println("[MovieKing v116] Plain TS. Passing.")
                                output.write(rawData)
                            } else {
                                // [v116] 단순화된 복호화 시도 (Mega Scan 제거)
                                val decrypted = tryDecryptSimple(rawData, seq)
                                if (decrypted != null) {
                                    output.write(decrypted)
                                } else {
                                    println("[MovieKing v116] Decrypt Failed. Sending RAW.")
                                    output.write(rawData)
                                }
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v116] Proxy Error: $e") }
        }

        private fun tryDecryptSimple(data: ByteArray, seq: Long): ByteArray? {
            // IV 준비: 1. Hex IV(있으면), 2. Zero IV, 3. Sequence IV (Target)
            val ivs = mutableListOf<ByteArray>()
            
            // Priority 1: Hex IV (명시적)
            if (!playlistIv.isNullOrEmpty()) {
                try {
                    val hex = playlistIv!!.removePrefix("0x")
                    val iv = ByteArray(16)
                    hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                    ivs.add(iv)
                } catch(e:Exception) {}
            }
            
            // Priority 2: Sequence IV (Target Sequence)
            val seqIv = ByteArray(16)
            for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
            ivs.add(seqIv)

            // Priority 3: Zero IV (Fallback)
            ivs.add(ByteArray(16))

            val checkSize = 188 * 2
            if (data.size < checkSize) return null

            // [단순 전수 조사] 키 후보(2~3개) * IV 후보(2~3개) = 최대 9번 연산 (순식간)
            for ((keyIdx, key) in keyCandidates.withIndex()) {
                for ((ivIdx, iv) in ivs.withIndex()) {
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val head = cipher.update(data.take(checkSize).toByteArray())
                        
                        // 2-Sync 검증
                        if (head.isNotEmpty() && head[0] == 0x47.toByte() && head.size > 188 && head[188] == 0x47.toByte()) {
                            println("[MovieKing v116] JACKPOT! Key#$keyIdx, IV#$ivIdx")
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
