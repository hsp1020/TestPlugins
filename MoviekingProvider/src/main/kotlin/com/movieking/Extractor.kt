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
 * v129: Full Browser Spoofing (The Chameleon)
 * [사용자 피드백 반영]
 * 1. 완벽한 위장: 프록시 서버의 모든 요청 헤더를 실제 Chrome 브라우저와 100% 동일하게 구성 (Sec-Fetch 등 포함).
 * 2. 아키텍처 롤백: 가장 안정적이었던 v124(프록시 방식)로 돌아가되, '통신'만 위장술을 사용.
 * 3. 0x47 검증 완화: TS 헤더 체크를 유연하게 하여 정답 키를 가지고도 재연산하는 병목 제거.
 * 4. 전역 캐시: 한 번 찾은 키는 앱이 꺼질 때까지 절대 잊지 않음.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    
    // 실제 PC 크롬의 User-Agent
    private val SPOOF_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        
        // 전역 정적 캐시 (영상 변경 시에만 초기화)
        @Volatile private var globalKey: ByteArray? = null
        @Volatile private var globalIvType: Int = -1
        @Volatile private var lastVideoId: String? = null
        private val globalSeqMap = ConcurrentHashMap<String, Long>()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v129] Browser Spoofing Mode ===")
        try {
            val videoId = extractVideoIdDeep(url)
            
            // [핵심 1] 브라우저와 100% 동일한 헤더 구성 (순서 중요)
            val spoofHeaders = mapOf(
                "User-Agent" to SPOOF_UA,
                "Accept" to "*/*",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Origin" to "https://player-v1.bcbc.red",
                "Referer" to "https://player-v1.bcbc.red/",
                "Pragma" to "no-cache",
                "Cache-Control" to "no-cache"
            )
            
            // 영상 변경 감지
            if (lastVideoId != videoId) {
                globalKey = null
                globalSeqMap.clear()
                lastVideoId = videoId
            }

            // M3U8 가져오기 (위장 헤더 사용)
            val playerHtml = app.get(url, headers = spoofHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = spoofHeaders).text
            
            // 키 정보 추출
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val candidates = if (keyMatch != null) solveKeyCandidatesCombinatorial(spoofHeaders, keyMatch.groupValues[1]) else emptyList()
            val hexIv = keyMatch?.groupValues?.get(2)
            
            // 프록시 서버 구동 (위장 헤더 주입)
            if (proxyServer == null || !proxyServer!!.isActive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            proxyServer!!.updateParams(spoofHeaders, hexIv, candidates)
            
            // Playlist 재작성 및 매핑
            val proxyRoot = "http://127.0.0.1:${proxyServer!!.port}/$videoId"
            val lines = playlistRes.lines()
            val newLines = mutableListOf<String>()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            
            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) continue
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    globalSeqMap[segmentUrl] = currentSeq
                    newLines.add("$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}")
                    currentSeq++
                } else newLines.add(line)
            }
            
            proxyServer!!.setPlaylist(newLines.joinToString("\n"))
            
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/" 
            })
        } catch (e: Exception) { println("[MovieKing v129] Error: $e") }
    }

    private fun extractVideoIdDeep(url: String): String {
        return try {
            val token = url.split("/v1/").getOrNull(1)?.split(".")?.getOrNull(1)
            val decoded = String(Base64.decode(token!!, Base64.URL_SAFE))
            Regex(""""id"\s*:\s*(\d+)""").find(decoded)?.groupValues?.get(1) ?: "ID_ERR"
        } catch (e: Exception) { "ID_ERR" }
    }

    private suspend fun solveKeyCandidatesCombinatorial(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val b64 = Base64.decode(encStr, Base64.DEFAULT)
            if (b64.size == 16) list.add(b64)
            if (b64.size >= 22) {
                val segs = mutableListOf<ByteArray>()
                var idx = 0
                val gaps = listOf(0, 2, 2, 2, 2)
                for (i in 0..3) {
                    idx += gaps[i]; if (idx+4 <= b64.size) segs.add(b64.copyOfRange(idx, idx+4)); idx += 4
                }
                generatePermutations(listOf(0, 1, 2, 3)).forEach { p ->
                    val k = ByteArray(16)
                    for (j in 0..3) System.arraycopy(segs[p[j]], 0, k, j * 4, 4)
                    list.add(k)
                }
            }
        } catch (e: Exception) {}
        return list.distinctBy { it.contentHashCode() }
    }

    private fun generatePermutations(list: List<Int>): List<List<Int>> {
        if (list.isEmpty()) return listOf(emptyList())
        val result = mutableListOf<List<Int>>()
        for (i in list.indices) {
            val elem = list[i]
            val rest = list.take(i) + list.drop(i + 1)
            for (p in generatePermutations(rest)) result.add(listOf(elem) + p)
        }
        return result
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var headers: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var m3u8: String = ""
        @Volatile private var candidates: List<ByteArray> = emptyList()

        fun isActive() = isRunning && serverSocket?.isClosed == false
        fun start() {
            serverSocket = ServerSocket(0).apply { port = localPort }
            isRunning = true
            thread(isDaemon = true) { 
                while (isRunning) {
                    try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                }
            }
        }
        fun stop() { isRunning = false; serverSocket?.close() }
        fun updateParams(h: Map<String, String>, iv: String?, c: List<ByteArray>) { headers = h; playlistIv = iv; candidates = c }
        fun setPlaylist(m: String) { m3u8 = m }

        private fun handleClient(socket: Socket) = thread {
            try {
                // [핵심 2] 소켓 옵션 최적화 (지연 방지)
                socket.tcpNoDelay = true
                socket.soTimeout = 30000 
                
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    output.write(m3u8.toByteArray())
                } else if (path.contains("/proxy")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    val seq = globalSeqMap[targetUrl] ?: 0L
                    
                    runBlocking {
                        // [핵심 3] 서버 요청 시 위장된 헤더 사용
                        val res = app.get(targetUrl, headers = headers)
                        if (res.isSuccessful) {
                            val raw = res.body.bytes()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            
                            // 1. 전역 캐시가 있으면 무조건 복호화 시도 (가장 빠름)
                            globalKey?.let { k ->
                                val dec = decrypt(raw, k, globalIvType, seq)
                                if (dec != null) {
                                    output.write(dec)
                                    return@runBlocking
                                }
                            }
                            
                            // 2. 캐시가 없으면(최초 1회) 브루트포스 진행
                            if (raw.isNotEmpty() && isValidTs(raw)) {
                                output.write(raw) // 이미 평문이면 그냥 보냄
                            } else {
                                val dec = bruteForce(raw, seq)
                                if (dec != null) output.write(dec) else output.write(raw)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { socket.close() }
        }

        private fun decrypt(data: ByteArray, key: ByteArray, ivType: Int, seq: Long): ByteArray? {
            return try {
                val iv = getIv(ivType, seq)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val result = cipher.doFinal(data)
                // [핵심 4] 검증 로직 완화: 첫 바이트뿐만 아니라 Sync Byte 검색
                if (result.isNotEmpty() && isValidTs(result)) result else null
            } catch (e: Exception) { null }
        }

        private fun bruteForce(data: ByteArray, seq: Long): ByteArray? {
            if (data.size < 376) return null
            val ivs = getIvList(seq)
            for ((kIdx, key) in candidates.withIndex()) {
                for ((iIdx, iv) in ivs.withIndex()) {
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val head = cipher.update(data.take(376).toByteArray())
                        
                        // 완화된 TS 검증 로직 적용
                        if (isValidTs(head)) {
                            println("[MovieKing v129] JACKPOT Found! Caching Key.")
                            globalKey = key; globalIvType = iIdx
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            return cipher.doFinal(data)
                        }
                    } catch (e: Exception) {}
                }
            }
            return null
        }

        // [중요] TS 헤더 검증 유연화 함수 (0x47이 첫 바이트가 아니어도 됨)
        private fun isValidTs(data: ByteArray): Boolean {
            if (data.size < 189) return false
            // 앞부분 188바이트 내에서 0x47(Sync Byte)을 찾음
            for (i in 0 until 188) {
                if (data[i] == 0x47.toByte()) {
                    // 다음 패킷 헤더 위치도 0x47인지 확인 (연속성 검증)
                    if (i + 188 < data.size && data[i + 188] == 0x47.toByte()) return true
                }
            }
            return false
        }

        private fun getIvList(seq: Long): List<ByteArray> {
            val list = mutableListOf<ByteArray>()
            playlistIv?.let { pIv ->
                try {
                    val iv = ByteArray(16)
                    pIv.removePrefix("0x").chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                    list.add(iv)
                } catch(e: Exception) {}
            }
            val sIv = ByteArray(16)
            for (i in 0..7) sIv[15 - i] = (seq shr (i * 8)).toByte()
            list.add(sIv); list.add(ByteArray(16))
            return list
        }
        private fun getIv(type: Int, seq: Long) = getIvList(seq).let { if (type in it.indices) it[type] else ByteArray(16) }
    }
}
