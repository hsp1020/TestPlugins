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
 * v131: Unbreakable Server Engine (Fix 3002/Timeout)
 * [문제 원인]
 * v130에서 서버 스레드가 비정상 종료(Timeout/Error)되어도 'isAlive' 플래그가 true로 남아있어,
 * 다음 요청 시 죽은 서버에 연결을 시도하다 타임아웃(3002) 발생.
 * [해결책]
 * 1. 서버 감시 강화: 단순 boolean이 아닌 실제 스레드 생존 여부(Thread.isAlive) 확인.
 * 2. 좀비 부활: accept 루프가 죽으면 즉시 감지하고 재시작.
 * 3. 예외 격리: 클라이언트 핸들링 중 에러가 서버 전체를 죽이지 않도록 try-catch 범위 수정.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        // [v131] 싱글톤 유지
        private val proxyServer = ProxyWebServer()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v131] getUrl Start ===")
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            val keyUrl = keyMatch?.groupValues?.get(1)
            val keyData = if (keyUrl != null) fetchKeyData(baseHeaders, keyUrl) else null
            
            // [v131] 서버 상태 정밀 점검 (죽었으면 살려내라)
            if (!proxyServer.isHealthy()) {
                println("[MovieKing v131] Server is unhealthy. Restarting...")
                proxyServer.stop()
                proxyServer.start()
            }
            proxyServer.updateSession(baseHeaders, hexIv, keyData, videoId)
            
            val seqMap = ConcurrentHashMap<String, Long>()
            val lines = playlistRes.lines()
            val newLines = mutableListOf<String>()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            
            val port = proxyServer.port
            val proxyRoot = "http://127.0.0.1:$port"

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) continue
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    val fileName = segmentUrl.substringAfterLast("/")
                    seqMap[fileName] = currentSeq
                    newLines.add("$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}")
                    currentSeq++
                } else {
                    newLines.add(line)
                }
            }
            
            proxyServer.updateSeqMap(seqMap)
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v131] FATAL Error: $e") }
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

    private suspend fun fetchKeyData(h: Map<String, String>, kUrl: String): ByteArray? {
        return try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
            try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { encStr.toByteArray() }
        } catch (e: Exception) { null }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var serverThread: Thread? = null // [v131] 스레드 참조 보관
        @Volatile private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var keyData: ByteArray? = null
        @Volatile private var seqMap: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
        
        @Volatile private var currentVideoId: String = ""
        @Volatile private var confirmedKey: ByteArray? = null
        @Volatile private var confirmedIvType: Int = -1

        // [v131] 헬스 체크: 소켓이 열려있고 스레드가 살아있는지 확인
        fun isHealthy(): Boolean {
            return isRunning && serverSocket != null && !serverSocket!!.isClosed && serverThread != null && serverThread!!.isAlive
        }

        fun start() {
            if (isHealthy()) return
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                
                serverThread = thread(isDaemon = true) {
                    println("[MovieKing v131] Server Thread Started on Port $port")
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try {
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            if (isRunning) println("[MovieKing v131] Accept Error (Retrying): $e")
                        } 
                    }
                    println("[MovieKing v131] Server Thread Died")
                }
            } catch (e: Exception) { println("[MovieKing v131] Server Bind Failed: $e") }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
            try { serverThread?.interrupt(); serverThread = null } catch (e: Exception) {}
        }
        
        fun updateSession(h: Map<String, String>, iv: String?, kData: ByteArray?, vid: String) {
            currentHeaders = h; playlistIv = iv; keyData = kData
            if (currentVideoId != vid) {
                currentVideoId = vid; confirmedKey = null; confirmedIvType = -1
                seqMap.clear()
                println("[MovieKing v131] New Session: $vid")
            }
        }
        
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) {
            seqMap.putAll(map)
        }
        
        fun setPlaylist(p: String) {}

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 15000 
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
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
                                if (confirmedKey != null) {
                                    val dec = decryptDirect(rawData, confirmedKey!!, confirmedIvType, seq)
                                    if (dec != null) {
                                        output.write(dec)
                                        return@runBlocking
                                    } else { confirmedKey = null }
                                }
                                val dec = bruteForceLazy(rawData, seq)
                                if (dec != null) output.write(dec) else output.write(rawData)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun decryptDirect(data: ByteArray, key: ByteArray, ivType: Int, seq: Long): ByteArray? {
            return try {
                val iv = getIv(ivType, seq)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }

        private fun bruteForceLazy(data: ByteArray, seq: Long): ByteArray? {
            val ivs = getIvList(seq)
            val checkSize = 188 * 2
            if (data.size < checkSize || keyData == null) return null

            for (key in generateKeysLazy(keyData!!)) {
                for ((ivIdx, iv) in ivs.withIndex()) {
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val head = cipher.update(data.take(checkSize).toByteArray())
                        if (head.isNotEmpty() && head[0] == 0x47.toByte() && head.size > 188 && head[188] == 0x47.toByte()) {
                            println("[MovieKing v131] KEY LOCKED!")
                            confirmedKey = key
                            confirmedIvType = ivIdx
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            return cipher.doFinal(data)
                        }
                    } catch (e: Exception) {}
                }
            }
            return null
        }

        private fun generateKeysLazy(src: ByteArray): Sequence<ByteArray> = sequence {
            if (src.size == 16) yield(src)
            if (src.size > 16) {
                val slack = src.size - 16
                val distributions = generateDistributions(slack, 5)
                val allPerms = generatePermutations(listOf(0, 1, 2, 3))
                for (gaps in distributions) {
                    val segs = arrayOfNulls<ByteArray>(4)
                    var idx = gaps[0]
                    var valid = true
                    for (i in 0 until 4) {
                        if (idx + 4 <= src.size) {
                            segs[i] = src.copyOfRange(idx, idx + 4)
                            idx += 4 + gaps[i+1]
                        } else { valid = false; break }
                    }
                    if (valid) {
                        for (perm in allPerms) {
                            val k = ByteArray(16)
                            for (j in 0 until 4) System.arraycopy(segs[perm[j]]!!, 0, k, j * 4, 4)
                            yield(k)
                        }
                    }
                }
            }
        }

        private fun generateDistributions(n: Int, k: Int): List<List<Int>> {
            if (k == 1) return listOf(listOf(n))
            val result = mutableListOf<List<Int>>()
            for (i in 0..n) {
                for (sub in generateDistributions(n - i, k - 1)) result.add(listOf(i) + sub)
            }
            return result
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
