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
 * v128.0: Persistent Global Proxy
 * [분석 기반 수정 사항]
 * 1. 서버 재사용: getUrl이 호출되어도 서버를 끄지 않음 (기존 세션 유지).
 * 2. 전역 정적 캐시: confirmedKey를 static(Companion object)으로 관리하여 구간 이동 시 재연산 0초.
 * 3. 단일 스트림 전략: 데이터 요청을 프록시로 다시 일원화하여 v127의 서버 차단(404/Timeout) 원천 봉쇄.
 * 4. 세그먼트 중복 로딩 제거: 이미 정답 키를 알면 즉시 복호화 루틴으로 진입.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        
        // 전역 정적 변수: 구간 이동 및 재호출 시 정보 유지의 핵심
        @Volatile private var globalKey: ByteArray? = null
        @Volatile private var globalIvType: Int = -1
        @Volatile private var lastVideoId: String? = null
        private val globalSeqMap = ConcurrentHashMap<String, Long>()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            // 영상이 바뀌었을 때만 전역 캐시 초기화
            if (lastVideoId != videoId) {
                globalKey = null
                globalSeqMap.clear()
                lastVideoId = videoId
            }

            // 서버가 살아있으면 재사용 (stop() 호출 금지)
            if (proxyServer == null || !proxyServer!!.isActive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val candidates = if (keyMatch != null) solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            val hexIv = keyMatch?.groupValues?.get(2)
            
            proxyServer!!.updateParams(baseHeaders, hexIv, candidates)
            
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
        } catch (e: Exception) { }
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
                    idx += gaps[i]; segs.add(b64.copyOfRange(idx, idx + 4)); idx += 4
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
            thread(isDaemon = true) { while (isRunning) try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
        }
        fun stop() { isRunning = false; serverSocket?.close() }
        fun updateParams(h: Map<String, String>, iv: String?, c: List<ByteArray>) { headers = h; playlistIv = iv; candidates = c }
        fun setPlaylist(m: String) { m3u8 = m }

        private fun handleClient(socket: Socket) = thread {
            try {
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
                        val res = app.get(targetUrl, headers = headers)
                        if (res.isSuccessful) {
                            val raw = res.body.bytes()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            
                            // 1. 이미 정답 키를 안다면 즉시 복호화 (Seek 렉 제거 핵심)
                            globalKey?.let { k ->
                                decrypt(raw, k, globalIvType, seq)?.let { output.write(it); return@runBlocking }
                            }
                            
                            // 2. 모를 때만 딱 한 번 실행
                            if (raw.isNotEmpty() && raw[0] == 0x47.toByte()) output.write(raw)
                            else {
                                bruteForce(raw, seq)?.let { output.write(it) } ?: output.write(raw)
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
                cipher.doFinal(data).takeIf { it.isNotEmpty() && it[0] == 0x47.toByte() }
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
                        if (head.size >= 189 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()) {
                            globalKey = key; globalIvType = iIdx
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            return cipher.doFinal(data)
                        }
                    } catch (e: Exception) {}
                }
            }
            return null
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
