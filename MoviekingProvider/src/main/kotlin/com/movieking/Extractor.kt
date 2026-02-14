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
 * v127.0: Selective Proxy Strategy
 * [변경 사항]
 * 1. 영상 데이터 프록시 제거: 플레이어가 서버(m1.ms)에서 직접 TS 파일을 받도록 설계하여 30초 렉 원천 차단.
 * 2. 열쇠(Key) 전용 프록시: 복호화에 필요한 16바이트 정답 키만 우리 프록시 서버가 가공하여 전달.
 * 3. 절대 경로 강제 변환: M3U8 내의 상대 경로를 원본 서버의 절대 주소로 변환하여 플레이어 직결 유도.
 * 4. 병목 완전 해소: 구간 이동(Seek) 시 플레이어 순정 로직이 작동하여 지연 시간이 0초에 수렴.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        @Volatile private var cachedKey: ByteArray? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v127.0] Seek-Optimized Mode Start ===")
        try {
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red",
                "User-Agent" to DESKTOP_UA
            )
            
            // 1. 원본 Manifest 및 정보 획득
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            // 2. 정답 키 탐색 (첫 로딩 시 단 1회 수행)
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            if (keyMatch != null) {
                val candidates = solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1])
                val firstSegment = playlistRes.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                val segmentUrl = if (firstSegment?.startsWith("http") == true) firstSegment else "${m3u8Url.substringBeforeLast("/")}/$firstSegment"
                
                // 첫 세그먼트로 브루트포스 검증 후 결과 캐싱
                cachedKey = findRealKey(segmentUrl, baseHeaders, candidates)
            }

            // 3. 열쇠 전용 프록시 서버 시작
            if (proxyServer == null || !proxyServer!!.isActive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val localRoot = "http://127.0.0.1:${proxyServer!!.port}"
            val keyProxyUrl = "$localRoot/key.bin"

            // 4. Manifest 개조 (TS는 원본 서버로, KEY는 로컬 프록시로)
            val lines = playlistRes.lines()
            val rewrittenM3u8 = StringBuilder()
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-KEY") -> {
                        // 키 주소만 우리 프록시로 변경
                        rewrittenM3u8.append("""#EXT-X-KEY:METHOD=AES-128,URI="$keyProxyUrl"""").append("\n")
                    }
                    line.isNotBlank() && !line.startsWith("#") -> {
                        // 영상 주소는 서버 절대 경로로 변환 (프록시 거치지 않음)
                        val absoluteUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                        rewrittenM3u8.append(absoluteUrl).append("\n")
                    }
                    else -> rewrittenM3u8.append(line).append("\n")
                }
            }
            
            proxyServer!!.setManifest(rewrittenM3u8.toString())
            
            // 5. 플레이어에게 개조된 M3U8 전달
            callback(newExtractorLink(name, name, "$localRoot/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/" 
            })
            
            println("=== [MovieKing v127.0] Optimization Complete ===")
        } catch (e: Exception) { println("[MovieKing v127.0] Error: $e") }
    }

    private suspend fun findRealKey(segmentUrl: String, headers: Map<String, String>, candidates: List<ByteArray>): ByteArray? {
        return try {
            val data = app.get(segmentUrl, headers = headers).body.bytes()
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
                    // 플레이어가 키를 요청하면 가공된 16바이트 정답 키만 반환
                    val key = cachedKey
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
