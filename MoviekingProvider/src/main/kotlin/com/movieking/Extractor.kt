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
 * v80: High-Speed Pre-Solving Engine
 * [수정 사항]
 * 1. Pre-Solving: 영상 요청 전 미리 키 조립 완료 (타이밍 문제 해결)
 * 2. AES Deep Scan: 복호화 후 2KB 내에서 싱크 바이트 탐색 (Junk 함정 해결)
 * 3. Real-time Streaming: 메모리 버퍼링 없이 즉시 전송 (Broken Pipe 해결)
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v80] getUrl Start (Turbo Mode) ===")
        try {
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val realKeyUrl = keyMatch?.groupValues?.get(1)
            val tagIv = keyMatch?.groupValues?.get(2)

            // [핵심] 영상 요청이 오기 전 미리 키를 조립해둠
            proxyServer!!.prepareTurbo(baseHeaders, realKeyUrl, tagIv)
            
            val port = proxyServer!!.port
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "http://127.0.0.1:$port/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "http://127.0.0.1:$port/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v80] Error: $e") }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        
        // 미리 준비된 키 후보들
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
        fun setPlaylist(p: String) { currentPlaylist = p }

        fun prepareTurbo(h: Map<String, String>, kUrl: String?, iv: String?) {
            currentHeaders = h; playlistIv = iv; confirmedKey = null; confirmedIvMode = -1; confirmedOffset = 0
            if (kUrl == null) return
            thread { // 비동기로 미리 키 조립
                runBlocking {
                    try {
                        val jsonStr = app.get(kUrl, headers = h).text
                        val decodedJson = if (jsonStr.startsWith("{")) jsonStr else String(Base64.decode(jsonStr, Base64.DEFAULT))
                        val encKeyStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decodedJson)?.groupValues?.get(1) ?: return@runBlocking
                        val ruleJson = Regex(""""rule"\s*:\s*(\{.*?\})""").find(decodedJson)?.groupValues?.get(1) ?: return@runBlocking
                        
                        val noise = Regex(""""noise_length"\s*:\s*(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 2
                        val size = Regex(""""segment_sizes"\s*:\s*\[(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 4
                        val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)

                        val list = mutableListOf<ByteArray>()
                        try { list.add(Base64.decode(encKeyStr, Base64.DEFAULT)) } catch (e: Exception) {}
                        list.add(encKeyStr.toByteArray())

                        keyCandidates = list.mapNotNull { src ->
                            val segments = mutableListOf<ByteArray>()
                            for (i in 0 until 4) {
                                val start = i * (size + noise)
                                if (start + size <= src.size) segments.add(src.copyOfRange(start, start + size))
                            }
                            if (segments.size == 4) {
                                val k = ByteArray(16)
                                for (j in 0 until 4) System.arraycopy(segments[perm[j]], 0, k, j * 4, 4)
                                k
                            } else null
                        }
                        println("[MovieKing v80] Turbo Preparation Ready. Candidates: ${keyCandidates.size}")
                    } catch (e: Exception) {}
                }
            }
        }

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
                            val stream = BufferedInputStream(res.body.byteStream())
                            val probeSize = 65536 // 64KB만 먼저 읽어서 검증
                            stream.mark(probeSize)
                            val buffer = ByteArray(probeSize)
                            val read = stream.read(buffer)
                            stream.reset()

                            if (confirmedKey == null) findJackpotDeep(buffer, read, seq)

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (confirmedKey != null) {
                                val k = confirmedKey!!; val off = confirmedOffset
                                var total = 0L
                                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(getIv(confirmedIvMode, seq)))
                                
                                // 스트리밍 복호화
                                val sBuf = ByteArray(16384); var c: Int
                                while (stream.read(sBuf).also { c = it } != -1) {
                                    val dec = cipher.update(sBuf, 0, c)
                                    if (dec != null) {
                                        val start = if (total < off) (off - total).toInt().coerceAtLeast(0) else 0
                                        if (dec.size > start) output.write(dec, start, dec.size - start)
                                        total += dec.size
                                    }
                                }
                                val last = cipher.doFinal()
                                if (last != null && total + last.size > off) {
                                    val start = if (total < off) (off - total).toInt().coerceAtLeast(0) else 0
                                    output.write(last, start, last.size - start)
                                }
                            } else {
                                stream.copyTo(output)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v80] Stream Error: $e") }
        }

        private fun findJackpotDeep(data: ByteArray, len: Int, seq: Long) {
            for (key in keyCandidates) {
                for (mode in 0..2) {
                    val iv = getIv(mode, seq)
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val decrypted = cipher.doFinal(data.take(8192).toByteArray())
                        
                        // [핵심] 복호화된 데이터의 2KB 내에서 0x47(TS Sync) 탐색
                        for (off in 0..2048) {
                            if (decrypted[off] == 0x47.toByte() && decrypted[off + 188] == 0x47.toByte()) {
                                println("[MovieKing v80] TURBO JACKPOT! Key Found. Offset: $off, IV Mode: $mode")
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
    }
}
