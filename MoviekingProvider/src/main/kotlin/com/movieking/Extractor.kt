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
 * v102: Forensic Logging & Pure v87 Logic
 * [수정 사항]
 * 1. v87 로직 보존: 복호화 및 재생 로직은 제공해주신 원본 그대로 유지.
 * 2. 독립 딥스캔: 1MB 스캔은 별도 함수에서 실행, 재생 변수에 영향 없음 (죽기 싫음).
 * 3. 포렌식 로그: RAW HEADER, Key JSON, Winning Key, Offset, 재생 원인 로그 전수 반영.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v102] getUrl Start (v87 Pure Logic) ===")
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val candidates = if (keyMatch != null) solveKeyCandidatesSync(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            proxyServer!!.updateSession(baseHeaders, keyMatch?.groupValues?.get(2), candidates)
            
            val port = proxyServer!!.port
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            
            val proxyRoot = "http://127.0.0.1:$port/$videoId"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/" 
            })
        } catch (e: Exception) { println("[MovieKing v102] FATAL Error: $e") }
    }

    private fun extractVideoIdDeep(url: String): String {
        try {
            val segments = url.split("/", ".", "?", "&")
            for (seg in segments) {
                if (seg.length > 20) {
                    val decoded = try { String(Base64.decode(seg, Base64.DEFAULT)) } catch (e: Exception) { "" }
                    if (decoded.contains("\"id\":")) {
                        val hiddenId = Regex(""""id"\s*:\s*(\d+)""").find(decoded)?.groupValues?.get(1)
                        if (hiddenId != null) return hiddenId
                    }
                }
            }
        } catch (e: Exception) {}
        val numbers = Regex("""\d{5,}""").findAll(url).map { it.value }.toList()
        return if (numbers.isNotEmpty()) numbers.last() else "ID_ERROR"
    }

    private suspend fun solveKeyCandidatesSync(h: Map<String, String>, kUrl: String): List<ByteArray> {
        return try {
            val jsonStr = app.get(kUrl, headers = h).text
            // [포렌식 로그 1] Key JSON 원문 출력
            println("[MovieKing v102] Key JSON: $jsonStr")

            val decodedJson = if (jsonStr.startsWith("{")) jsonStr else String(Base64.decode(jsonStr, Base64.DEFAULT))
            val encKeyStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decodedJson)?.groupValues?.get(1) ?: return emptyList()
            val ruleJson = Regex(""""rule"\s*:\s*(\{.*?\})""").find(decodedJson)?.groupValues?.get(1) ?: return emptyList()
            
            val noise = Regex(""""noise_length"\s*:\s*(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 2
            val size = Regex(""""segment_sizes"\s*:\s*\[(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 4
            val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)

            val sources = mutableListOf<ByteArray>()
            try { sources.add(Base64.decode(encKeyStr, Base64.DEFAULT)) } catch (e: Exception) {}
            sources.add(encKeyStr.toByteArray())

            sources.mapNotNull { src ->
                val segments = mutableListOf<ByteArray>()
                var currentOffset = 0
                for (i in 0 until 4) {
                    if (currentOffset + size <= src.size) {
                        segments.add(src.copyOfRange(currentOffset, currentOffset + size))
                        currentOffset += (size + noise)
                    }
                }
                if (segments.size == 4) {
                    val k = ByteArray(16)
                    for (j in 0 until 4) System.arraycopy(segments[perm[j]], 0, k, j * 4, 4)
                    k
                } else null
            }
        } catch (e: Exception) { emptyList() }
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
                            
                            // [포렌식 로그 2] RAW HEADER 출력 (64바이트)
                            val hexHeader = rawData.take(64).joinToString(" ") { "%02X".format(it) }
                            println("[MovieKing v102] RAW HEADER: $hexHeader")

                            // [유저 명령] 1MB 딥스캔 로그 (재생 로직 변수와 완전히 분리된 독립 실행)
                            logOnlyDeepScan(rawData, seq)

                            // v87 원본 복호화 시도
                            if (confirmedKey == null) findJackpot(rawData, seq)

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (confirmedKey != null) {
                                // [포렌식 로그 3, 4] 성공 시 정보 출력
                                println("[MovieKing v102] Result: Sending DECRYPTED data")
                                println("[MovieKing v102] Winning Key (Hex): ${confirmedKey!!.joinToString(""){ "%02X".format(it) }}")
                                println("[MovieKing v102] JACKPOT Offset: $confirmedOffset")
                                
                                val result = decryptAes(rawData, confirmedKey!!, getIv(confirmedIvMode, seq))
                                if (result.size > confirmedOffset) output.write(result, confirmedOffset, result.size - confirmedOffset)
                            } else {
                                // [포렌식 로그 5] 재생 원인 추적
                                println("[MovieKing v102] JACKPOT NOT FOUND. Passing through RAW data")
                                output.write(rawData)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v102] Proxy Error: $e") }
        }

        // [v102 핵심] 로그 출력용 1MB 딥스캔 (실제 재생 변수를 절대 수정하지 않음)
        private fun logOnlyDeepScan(data: ByteArray, seq: Long) {
            val scanLimit = minOf(data.size - 200, 1048576) // 1MB
            
            // 생 데이터 스캔
            for (off in 0..scanLimit) {
                if (data[off] == 0x47.toByte() && data[off + 188] == 0x47.toByte()) {
                    println("[MovieKing v102] DEEP SCAN TRACE: Raw 0x47 found at Offset: $off")
                    return
                }
            }
            // 복호화 대입 스캔
            for (key in keyCandidates) {
                for (mode in 0..2) {
                    try {
                        val decrypted = decryptAes(data.take(scanLimit + 200).toByteArray(), key, getIv(mode, seq))
                        for (off in 0..scanLimit) {
                            if (decrypted[off] == 0x47.toByte() && decrypted[off + 188] == 0x47.toByte()) {
                                println("[MovieKing v102] DEEP SCAN TRACE: Decrypted 0x47 could be matched at Offset: $off with KeyIdx: ${keyCandidates.indexOf(key)} Mode: $mode")
                                return
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            println("[MovieKing v102] DEEP SCAN TRACE: No sync byte found in 1MB scan.")
        }

        private fun findJackpot(data: ByteArray, seq: Long) {
            for (key in keyCandidates) {
                for (mode in 0..2) {
                    try {
                        val decrypted = decryptAes(data.take(8192).toByteArray(), key, getIv(mode, seq))
                        for (off in 0..2048) {
                            if (decrypted[off] == 0x47.toByte() && decrypted[off + 188] == 0x47.toByte()) {
                                confirmedKey = key; confirmedIvMode = mode; confirmedOffset = off
                                return
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
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
            return try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { ByteArray(0) }
        }
    }
}
