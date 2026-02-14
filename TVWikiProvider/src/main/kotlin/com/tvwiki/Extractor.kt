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
 * v127-2: Key-Wait Proxy Strategy
 * [수정 사항]
 * 1. Blocking Wait: 플레이어가 key.bin을 요청했을 때 정답 키가 없으면 최대 10초간 대기하여 404 에러 방지.
 * 2. Background Pre-compute: getUrl 시작과 동시에 키 탐색 스레드를 분리하여 연산 시간 확보.
 * 3. Direct TS Access: 영상 조각(TS)은 여전히 원본 서버(m1.ms)에서 직접 받으므로 구간 이동 렉 없음.
 * 4. Persistence: 한 번 찾은 키는 메모리에 영구 저장하여 다음 재생 시 즉시 응답.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        @Volatile private var confirmedKey: ByteArray? = null
        @Volatile private var isFindingKey = false
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v127-2] Seek-Optimized Start ===")
        try {
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red",
                "User-Agent" to DESKTOP_UA
            )
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            // 1. 키 후보군 및 첫 세그먼트 주소 확보
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            if (keyMatch != null && confirmedKey == null && !isFindingKey) {
                val candidates = solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1])
                val firstSegment = playlistRes.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                val segmentUrl = if (firstSegment?.startsWith("http") == true) firstSegment else "${m3u8Url.substringBeforeLast("/")}/$firstSegment"
                
                // 별도 스레드에서 즉시 정답 키 탐색 시작
                thread {
                    isFindingKey = true
                    runBlocking { confirmedKey = findRealKey(segmentUrl!!, baseHeaders, candidates) }
                    isFindingKey = false
                }
            }

            // 2. 프록시 서버 시작 (열쇠 전용)
            if (proxyServer == null || !proxyServer!!.isActive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val localRoot = "http://127.0.0.1:${proxyServer!!.port}"
            val lines = playlistRes.lines()
            val rewrittenM3u8 = StringBuilder()
            
            // 3. M3U8 재작성 (TS는 원본 서버로 연결)
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-KEY") -> {
                        rewrittenM3u8.append("""#EXT-X-KEY:METHOD=AES-128,URI="$localRoot/key.bin"""").append("\n")
                    }
                    line.isNotBlank() && !line.startsWith("#") -> {
                        val absoluteUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                        rewrittenM3u8.append(absoluteUrl).append("\n")
                    }
                    else -> rewrittenM3u8.append(line).append("\n")
                }
            }
            
            proxyServer!!.setManifest(rewrittenM3u8.toString())
            
            callback(newExtractorLink(name, name, "$localRoot/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/" 
            })
        } catch (e: Exception) { println("[MovieKing v127-2] Fatal Error: $e") }
    }

    private suspend fun findRealKey(segmentUrl: String, headers: Map<String, String>, candidates: List<ByteArray>): ByteArray? {
        return try {
            val res = app.get(segmentUrl, headers = headers)
            if (!res.isSuccessful) return null
            val data = res.body.bytes()
            if (data.size < 376) return null
            
            for (key in candidates) {
                try {
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
                    val head = cipher.update(data.take(376).toByteArray())
                    if (head.size >= 189 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()) return key
                } catch (e: Exception) {}
            }
            null
        } catch (e: Exception) { null }
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
        @Volatile private var currentManifest: String = ""

        fun isActive() = isRunning && serverSocket?.isClosed == false
        fun start() {
            try {
                serverSocket = ServerSocket(0).apply { port = localPort }
                isRunning = true
                thread(isDaemon = true) { 
                    while (isRunning) {
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun setManifest(m: String) { currentManifest = m }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@thread
                val path = if (line.contains(" ")) line.split(" ")[1] else return@thread
                val output = socket.getOutputStream()

                if (path.contains("playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    output.write(currentManifest.toByteArray())
                } else if (path.contains("key.bin")) {
                    // [v127-2 핵심] 키가 찾아질 때까지 최대 10초간 대기 (404 방지)
                    var waitCount = 0
                    while (confirmedKey == null && waitCount < 100) {
                        Thread.sleep(100)
                        waitCount++
                    }
                    
                    val key = confirmedKey
                    if (key != null) {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\n\r\n".toByteArray())
                        output.write(key)
                    } else {
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch (e2: Exception) {} }
        }
    }
}
