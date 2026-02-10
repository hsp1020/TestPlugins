package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch // [필수] 동기화 도구
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * v124-7: Sync Start Fix (3001 Error Solved)
 * [문제 원인 설명]
 * v124-6에서는 start() 내부가 비동기 스레드로 돌면서, 포트가 할당되기도 전에 getUrl이 리턴됨.
 * 플레이어는 유효하지 않은 포트(0)로 접속을 시도하여 3001 에러(데이터 없음) 발생.
 * [수정 사항]
 * CountDownLatch를 도입하여, '서버 포트 할당'이 완료될 때까지 getUrl 실행을 대기시킴.
 * -> 무조건 '준비 완료된 서버'의 URL만 반환.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        
        // [v124-7] 투기적 캐시 유지 (구간이동 렉 방지)
        @Volatile private var staticKey: ByteArray? = null
        @Volatile private var staticIvType: Int = -1
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v124-7] getUrl Start ===")
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            
            val candidates = if (keyMatch != null) solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            
            // [v124-7] 캐시가 있다면 로그 출력 (동작 확인용)
            if (staticKey != null) {
                println("[MovieKing v124-7] Using Speculative Cache for Seek Optimization")
            }

            // [v124-7] 서버 재시작 (Latch로 시작 완료 보장)
            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start() // 여기서 포트 할당될 때까지 무조건 기다림!
                updateSession(baseHeaders, hexIv, candidates)
            }
            
            val seqMap = ConcurrentHashMap<String, Long>()
            val lines = playlistRes.lines()
            val newLines = mutableListOf<String>()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            
            // [중요] Latch 덕분에 여기서 port는 무조건 유효한 값임
            val port = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$port/$videoId"

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) continue
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    
                    // [v124-7] 파일명 매핑 (Seek 해결)
                    val fileName = segmentUrl.substringAfterLast("/")
                    seqMap[fileName] = currentSeq
                    
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
        } catch (e: Exception) { println("[MovieKing v124-7] FATAL Error: $e") }
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

    private suspend fun solveKeyCandidatesCombinatorial(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }

            if (b64.size == 16) list.add(b64)

            listOf(b64).forEach { src ->
                if (src.size > 16) {
                    val slack = src.size - 16
                    val distributions = generateDistributions(slack, 5)
                    val allPerms = generatePermutations(listOf(0, 1, 2, 3))

                    for (gaps in distributions) {
                        try {
                            val segs = mutableListOf<ByteArray>()
                            var idx = gaps[0]
                            segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[1]
                            segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[2]
                            segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[3]
                            segs.add(src.copyOfRange(idx, idx + 4))

                            for (perm in allPerms) {
                                val k = ByteArray(16)
                                for (j in 0 until 4) System.arraycopy(segs[perm[j]], 0, k, j * 4, 4)
                                list.add(k)
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
            return list.distinctBy { it.contentHashCode() }
        } catch (e: Exception) { return emptyList() }
    }

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

    private fun generatePermutations(list: List<Int>): List<List<Int>> {
        if (list.isEmpty()) return listOf(emptyList())
        val result = mutableListOf<List<Int>>()
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
        
        // [v124-7] 동기화 래치 추가
        private val startLatch = CountDownLatch(1)

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) {
                    println("[MovieKing v124-7] Server Started on Port: $port")
                    // [핵심] 서버가 진짜 켜지면 래치 해제 (신호 발송)
                    startLatch.countDown()
                    
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
                
                // [핵심] 여기서 서버가 켜질 때까지 최대 3초간 기다림 (Blocking)
                if (!startLatch.await(3, TimeUnit.SECONDS)) {
                    println("[MovieKing v124-7] Server Start Timed Out!")
                }
            } catch (e: Exception) { 
                println("[MovieKing v124-7] Server Start Failed: $e")
                startLatch.countDown() // 실패해도 해제는 해야 무한대기 방지
            }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun updateSession(h: Map<String, String>, iv: String?, k: List<ByteArray>) {
            currentHeaders = h; playlistIv = iv; keyCandidates = k
        }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    output.write(currentPlaylist.toByteArray(charset("UTF-8")))
                } else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    
                    val fileName = targetUrl.substringAfterLast("/")
                    val seq = seqMap[fileName] ?: 0L
                    
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val rawData = res.body.bytes()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                            if (rawData.isNotEmpty() && rawData[0] == 0x47.toByte() && rawData.size > 188 && rawData[188] == 0x47.toByte()) {
                                output.write(rawData)
                            } else {
                                // [v124-7] 투기적 캐시 (Speculative Key) 확인
                                if (staticKey != null) {
                                    val dec = decryptDirect(rawData, staticKey!!, staticIvType, seq)
                                    if (dec != null) {
                                        output.write(dec)
                                        return@runBlocking
                                    } 
                                }
                                
                                val dec = bruteForceCombinatorial(rawData, seq)
                                if (dec != null) output.write(dec) else output.write(rawData)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        private fun decryptDirect(data: ByteArray, key: ByteArray, ivType: Int, seq: Long): ByteArray? {
            return try {
                val iv = getIv(ivType, seq)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }

        private fun bruteForceCombinatorial(data: ByteArray, seq: Long): ByteArray? {
            val ivs = getIvList(seq)
            val checkSize = 188 * 2
            if (data.size < checkSize) return null

            for ((keyIdx, key) in keyCandidates.withIndex()) {
                for ((ivIdx, iv) in ivs.withIndex()) {
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val head = cipher.update(data.take(checkSize).toByteArray())
                        
                        if (head.isNotEmpty() && head[0] == 0x47.toByte() && head.size > 188 && head[188] == 0x47.toByte()) {
                            println("[MovieKing v124-7] JACKPOT! Key#$keyIdx")
                            // 정답 키를 전역 변수에 저장 (캐싱)
                            staticKey = key
                            staticIvType = ivIdx
                            
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            return cipher.doFinal(data)
                        }
                    } catch (e: Exception) {}
                }
            }
            return null
        }

        private fun getIvList(seq: Long): List<ByteArray> {
            val ivs = mutableListOf<ByteArray>()
            if (!playlistIv.isNullOrEmpty()) {
                try {
                    val hex = playlistIv!!.removePrefix("0x")
                    val iv = ByteArray(16)
                    hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                    ivs.add(iv)
                } catch(e:Exception) { ivs.add(ByteArray(16)) }
            } else ivs.add(ByteArray(16))
            val seqIv = ByteArray(16)
            for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
            ivs.add(seqIv)
            ivs.add(ByteArray(16))
            return ivs
        }

        private fun getIv(type: Int, seq: Long): ByteArray {
            val list = getIvList(seq)
            return if (type in list.indices) list[type] else ByteArray(16)
        }
    }
}
