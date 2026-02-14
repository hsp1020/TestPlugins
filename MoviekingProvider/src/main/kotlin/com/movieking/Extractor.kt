package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/**
 * v124-7: TCP Connection Pooling & Keep-Alive Tuning
 * [수정 사항]
 * 1. Persistent Connection: 'Connection: keep-alive' 및 'Keep-Alive: timeout=60' 헤더를 추가하여 TCP 세션 유지.
 * 2. Header Synchronization: 원본 서버가 브라우저의 지속 연결로 인식하게 하여 ERR_EMPTY_RESPONSE 방어.
 * 3. Proxy Optimization: 소켓 응답 후 스트림을 즉시 닫지 않고 버퍼를 비워 연결 유지력 향상.
 * 4. Pacing Adjustment: 세마포어 수치를 조정하여 동시성 효율 최적화.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        
        @Volatile private var globalConfirmedKey: ByteArray? = null
        @Volatile private var globalConfirmedIvType: Int = -1
        @Volatile private var globalLastVideoId: String? = null
        private val globalSeqMap = ConcurrentHashMap<String, Long>()
        
        // 동시 요청 속도 제한 (Keep-Alive 세션 효율을 위해 5개로 확장)
        private val requestSemaphore = Semaphore(5)
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red",
                "User-Agent" to DESKTOP_UA,
                "Connection" to "keep-alive",
                "Keep-Alive" to "timeout=60, max=100" // 연결 유지 속성 명시
            )
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            val candidates = if (keyMatch != null) solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            
            if (globalLastVideoId != videoId) {
                globalConfirmedKey = null
                globalConfirmedIvType = -1
                globalSeqMap.clear()
                globalLastVideoId = videoId
            }

            if (proxyServer == null || !proxyServer!!.isActive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            proxyServer!!.updateParams(baseHeaders, hexIv, candidates)
            
            val lines = playlistRes.lines()
            val newLines = mutableListOf<String>()
            val currentSeqHeader = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            var tempSeq = currentSeqHeader
            
            val proxyRoot = "http://127.0.0.1:${proxyServer!!.port}/$videoId"

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) continue
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    globalSeqMap[segmentUrl] = tempSeq
                    newLines.add("$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}")
                    tempSeq++
                } else {
                    newLines.add(line)
                }
            }
            
            proxyServer!!.setPlaylist(newLines.joinToString("\n"))
            
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v124-7] getUrl Error: $e") }
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
                    idx += gaps[i]
                    segs.add(b64.copyOfRange(idx, idx + 4))
                    idx += 4
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
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var keyCandidates: List<ByteArray> = emptyList()

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
        fun updateParams(h: Map<String, String>, iv: String?, k: List<ByteArray>) {
            currentHeaders = h; playlistIv = iv; keyCandidates = k
        }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.tcpNoDelay = true // 지연 최소화
                socket.soTimeout = 30000
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@thread
                val path = if (line.contains(" ")) line.split(" ")[1] else return@thread
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                    output.write(currentPlaylist.toByteArray())
                } else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    val seq = globalSeqMap[targetUrl] ?: 0L
                    
                    runBlocking {
                        requestSemaphore.acquire()
                        try {
                            var rawData: ByteArray? = null
                            for (retry in 1..3) {
                                try {
                                    // app.get 호출 시 내부적으로 OkHttp Connection Pool이 작동함
                                    val res = app.get(targetUrl, headers = currentHeaders, timeout = 20)
                                    if (res.isSuccessful) {
                                        rawData = res.body.bytes()
                                        if (rawData.isNotEmpty()) break
                                    }
                                } catch (e: Exception) {
                                    if (retry == 3) throw e
                                    delay(150L * retry)
                                }
                            }

                            if (rawData != null) {
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                                if (rawData.isNotEmpty() && rawData[0] == 0x47.toByte()) {
                                    output.write(rawData)
                                } else {
                                    globalConfirmedKey?.let { k ->
                                        val dec = decryptDirect(rawData, k, globalConfirmedIvType, seq)
                                        if (dec != null) {
                                            output.write(dec)
                                            return@runBlocking
                                        }
                                    }
                                    val dec = bruteForceCombinatorial(rawData, seq)
                                    if (dec != null) output.write(dec) else output.write(rawData)
                                }
                            }
                        } finally {
                            requestSemaphore.release()
                        }
                    }
                }
                output.flush()
                socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun decryptDirect(data: ByteArray, key: ByteArray, ivType: Int, seq: Long): ByteArray? {
            return try {
                val iv = getIv(ivType, seq)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val result = cipher.doFinal(data)
                if (result.isNotEmpty() && result[0] == 0x47.toByte()) result else null
            } catch (e: Exception) { null }
        }

        private fun bruteForceCombinatorial(data: ByteArray, seq: Long): ByteArray? {
            if (data.size < 376) return null
            val ivs = getIvList(seq)
            for ((kIdx, key) in keyCandidates.withIndex()) {
                for ((iIdx, iv) in ivs.withIndex()) {
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val head = cipher.update(data.take(376).toByteArray())
                        if (head.size >= 189 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()) {
                            globalConfirmedKey = key
                            globalConfirmedIvType = iIdx
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
        
        private fun getIv(type: Int, seq: Long): ByteArray {
            val list = getIvList(seq)
            return if (type in list.indices) list[type] else ByteArray(16)
        }
    }
}
