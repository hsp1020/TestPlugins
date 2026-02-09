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
 * v93: Hex-Logging & Ultimate Brute-Force Mode
 * [수정 사항]
 * 1. 생 바이트 출력: 모든 .ts 조각의 헤더 64바이트를 Hex로 로그캣에 출력 (분석용)
 * 2. 1MB & 5-Sync: 1MB 범위 내에서 188바이트 간격 싱크 바이트 5개 연속 일치 확인
 * 3. ID 추출: 검증된 JWT Deep Parsing 로직 유지
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = extractVideoIdDeep(url)
        println("=== [MovieKing v93] getUrl Start (ID: $videoId) ===")

        try {
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val candidates = if (keyMatch != null) generateExhaustiveKeys(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            proxyServer!!.updateSession(baseHeaders, keyMatch?.groupValues?.get(2), candidates)
            
            val port = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$port/$videoId"
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v93] Error: $e") }
    }

    private fun extractVideoIdDeep(url: String): String {
        try {
            val token = url.split("/v1/").getOrNull(1)?.split(".")?.getOrNull(1)
            if (token != null) {
                val decoded = String(Base64.decode(token, Base64.URL_SAFE))
                Regex(""""id"\s*:\s*(\d+)""").find(decoded)?.groupValues?.get(1)?.let { return it }
            }
        } catch (e: Exception) {}
        return "ID_ERR"
    }

    private suspend fun generateExhaustiveKeys(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val json = app.get(kUrl, headers = h).text
            val clean = if (json.startsWith("{")) json else String(Base64.decode(json, Base64.DEFAULT))
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(clean)?.groupValues?.get(1) ?: return emptyList()
            val raw = encStr.toByteArray()
            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }
            listOf(raw, b64).forEach { src ->
                if (src.size >= 16) {
                    for (i in 0..src.size - 16) list.add(src.copyOfRange(i, i + 16))
                }
            }
        } catch (e: Exception) {}
        return list.distinctBy { it.contentHashCode() }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var keyCandidates: List<ByteArray> = emptyList()
        @Volatile private var confirmedKey: ByteArray? = null
        @Volatile private var confirmedIvMode: Int = -1
        @Volatile private var confirmedOffset: Int = 0

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>, iv: String?, k: List<ByteArray>) {
            currentHeaders = h; playlistIv = iv; keyCandidates = k
            confirmedKey = null; confirmedIvMode = -1; confirmedOffset = 0
        }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray() + currentPlaylist.toByteArray())
                } else if (path.contains("/proxy")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    val seq = Regex("""(\d+)\.ts""").find(targetUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val rawData = res.body.bytes()
                            
                            // [v93 핵심: 분석용 로그]
                            val hexHeader = rawData.take(64).joinToString(" ") { "%02X".format(it) }
                            println("[MovieKing v93] RAW HEADER (64B): $hexHeader")

                            if (confirmedKey == null) findJACKPOTBrute(rawData, seq)

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (confirmedKey != null || confirmedOffset > 0) {
                                val key = confirmedKey ?: byteArrayOf()
                                val result = if (key.isNotEmpty()) decryptAes(rawData, key, getIv(confirmedIvMode, seq)) else rawData
                                if (result.size > confirmedOffset) output.write(result, confirmedOffset, result.size - confirmedOffset)
                            } else output.write(rawData)
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v93] Proxy Error: $e") }
        }

        private fun findJACKPOTBrute(data: ByteArray, seq: Long) {
            val scanLimit = minOf(data.size - 1000, 1048576) // 1MB 스캔
            
            // 1. Plain TS 스캔 (5-Sync 검증)
            for (off in 0..scanLimit) {
                if (check5Sync(data, off)) {
                    println("[MovieKing v93] PLAIN TS VERIFIED. Offset: $off")
                    confirmedOffset = off; return
                }
            }

            // 2. 모든 키/IV 조합 무차별 대입 (1MB & 5-Sync)
            for (key in keyCandidates) {
                for (mode in 0..2) {
                    try {
                        val decrypted = decryptAes(data.take(scanLimit + 1000).toByteArray(), key, getIv(mode, seq))
                        for (off in 0..scanLimit) {
                            if (check5Sync(decrypted, off)) {
                                println("[MovieKing v93] BRUTE JACKPOT! Mode: $mode, Offset: $off")
                                confirmedKey = key; confirmedIvMode = mode; confirmedOffset = off
                                return
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            println("[MovieKing v93] FAILURE: All combinations failed 1MB 5-Sync check.")
        }

        private fun check5Sync(target: ByteArray, off: Int): Boolean {
            return try {
                target[off] == 0x47.toByte() &&
                target[off + 188] == 0x47.toByte() &&
                target[off + 376] == 0x47.toByte() &&
                target[off + 564] == 0x47.toByte() &&
                target[off + 752] == 0x47.toByte()
            } catch (e: Exception) { false }
        }

        private fun getIv(mode: Int, seq: Long): ByteArray {
            val iv = ByteArray(16)
            when (mode) {
                0 -> {
                    val hex = playlistIv?.removePrefix("0x") ?: ""
                    try { hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() } } catch(e:Exception) {}
                }
                1 -> for (i in 0..7) iv[15 - i] = (seq shr (i * 8)).toByte()
            }
            return iv
        }

        private fun decryptAes(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(data)
        }
    }
}
