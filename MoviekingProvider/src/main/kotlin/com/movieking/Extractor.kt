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
 * v125: Key-Only Proxy Strategy
 * [혁신적 변경 사항]
 * 1. 영상 조각(TS) 프록시 제거: 플레이어가 서버(m1.ms)에서 직접 영상을 받도록 하여 30초 렉 원천 차단.
 * 2. 키 프록시만 수행: 플레이어가 암호를 풀 때 필요한 '열쇠'만 우리 프록시 서버가 가공해서 전달.
 * 3. 사전 연산(Pre-computation): 첫 로딩 시 단 한 번만 브루트포스를 실행해 정답 키를 찾아냄.
 * 4. 구간 이동 최적화: 플레이어 순정 로직을 그대로 사용하므로 SEEK 딜레이가 일반 영상 수준으로 감소.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        @Volatile private var confirmedKey: ByteArray? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            // 1. 첫 로딩 시 정답 키를 미리 찾아냄 (딱 한 번만 실행)
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            if (keyMatch != null) {
                val candidates = solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1])
                val firstSegment = playlistRes.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                val segmentUrl = if (firstSegment?.startsWith("http") == true) firstSegment else "${m3u8Url.substringBeforeLast("/")}/$firstSegment"
                
                // 브루트포스로 '진짜 열쇠' 획득
                findRealKey(segmentUrl, baseHeaders, candidates)
            }

            // 2. 프록시 서버 시작 (영상 데이터가 아닌 '열쇠'만 서빙함)
            if (proxyServer == null || !proxyServer!!.isActive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val proxyKeyUrl = "http://127.0.0.1:${proxyServer!!.port}/key.bin"

            // 3. M3U8 수정: 영상 주소는 그대로 두되, KEY 주소만 우리 프록시로 교체
            val lines = playlistRes.lines()
            val newLines = mutableListOf<String>()
            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    // 키 요청만 우리 프록시로 유도
                    newLines.add("""#EXT-X-KEY:METHOD=AES-128,URI="$proxyKeyUrl"""")
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    // 영상 주소는 건드리지 않음 (서버에서 플레이어가 직접 다운로드)
                    val segmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    newLines.add(segmentUrl)
                } else {
                    newLines.add(line)
                }
            }
            
            val modifiedM3u8 = newLines.joinToString("\n")
            proxyServer!!.setM3u8(modifiedM3u8)
            
            callback(newExtractorLink(name, name, "http://127.0.0.1:${proxyServer!!.port}/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/" 
            })
        } catch (e: Exception) { println("[MovieKing v125] Error: $e") }
    }

    private fun extractVideoIdDeep(url: String): String {
        return try {
            val token = url.split("/v1/").getOrNull(1)?.split(".")?.getOrNull(1)
            val decoded = String(Base64.decode(token!!, Base64.URL_SAFE))
            Regex(""""id"\s*:\s*(\d+)""").find(decoded)?.groupValues?.get(1) ?: "ID_ERR"
        } catch (e: Exception) { "ID_ERR" }
    }

    private suspend fun findRealKey(segmentUrl: String, headers: Map<String, String>, candidates: List<ByteArray>) {
        try {
            val res = app.get(segmentUrl, headers = headers)
            if (!res.isSuccessful) return
            val data = res.body.bytes()
            if (data.size < 376) return

            for (key in candidates) {
                // IV는 대개 0이거나 세그먼트 번호지만, 키 서버에서 직접 찾는 방식이 가장 확실
                val iv = ByteArray(16) // 대부분 0으로 시작
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val head = cipher.update(data.take(376).toByteArray())
                if (head.size >= 189 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()) {
                    confirmedKey = key
                    println("[MovieKing v125] Real Key Found and Cached!")
                    return
                }
            }
        } catch (e: Exception) {}
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
        @Volatile private var m3u8Content: String = ""

        fun isActive() = isRunning && serverSocket?.isClosed == false
        fun start() {
            serverSocket = ServerSocket(0).apply { port = localPort }
            isRunning = true
            thread(isDaemon = true) { 
                while (isRunning) try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
            }
        }
        fun stop() { isRunning = false; serverSocket?.close() }
        fun setM3u8(content: String) { m3u8Content = content }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    output.write(m3u8Content.toByteArray())
                } else if (path.contains("key.bin")) {
                    // 플레이어가 열쇠를 달라고 하면 미리 찾아둔 정답 열쇠 16바이트만 전송
                    if (confirmedKey != null) {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\n\r\n".toByteArray())
                        output.write(confirmedKey)
                    } else {
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { socket.close() }
        }
    }
}
