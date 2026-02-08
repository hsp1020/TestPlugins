package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v69] getUrl Start (Extreme Debug Mode) ===")
        try {
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val port = proxyServer!!.updateSession(baseHeaders)
            val proxyBaseUrl = "http://127.0.0.1:$port"
            
            // [디버그] AES 태그 제거 확인
            val originalKeyLine = playlistRes.lines().find { it.contains("#EXT-X-KEY") }
            if (originalKeyLine != null) {
                println("[MovieKing v69] Detected AES Tag: $originalKeyLine -> REMOVING for stability")
            }
            
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")

            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyBaseUrl/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "$proxyBaseUrl/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/"
            })
        } catch (e: Exception) { println("[MovieKing v69] getUrl Error: $e") }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var solvedKey: ByteArray? = null
        @Volatile private var solvedOffset: Int = -1

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>) = port.also { 
            currentHeaders = h; solvedKey = null; solvedOffset = -1 
        }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray() + currentPlaylist.toByteArray())
                } else if (path.contains("/proxy")) {
                    val url = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    runBlocking {
                        val res = app.get(url, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val stream = BufferedInputStream(res.body.byteStream())
                            val buffer = ByteArray(65536) 
                            stream.mark(65536); val read = stream.read(buffer); stream.reset()

                            if (read > 0 && solvedKey == null) {
                                // [디버그] 데이터 샘플링
                                val headHex = buffer.take(16).joinToString(" ") { "%02X".format(it) }
                                println("[MovieKing v69] Analyzing Stream Start. Raw Bytes: $headHex")
                                findKeyAndOffsetWithDebug(buffer, read)
                            }

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (solvedKey != null) {
                                val k = solvedKey!!; val off = solvedOffset
                                val initialDec = ByteArray(read)
                                for (i in 0 until read) initialDec[i] = (buffer[i].toInt() xor k[(i % k.size)].toInt()).toByte()
                                
                                // [디버그] 복호화 성공 여부 샘플링
                                val decHead = initialDec.drop(off).take(8).joinToString(" ") { "%02X".format(it) }
                                println("[MovieKing v69] Stream Decrypted Head (should start with 47): $decHead")
                                
                                if (read > off) output.write(initialDec, off, read - off)
                                var total = read.toLong()
                                val sBuf = ByteArray(16384); var c: Int
                                while (stream.read(sBuf).also { c = it } != -1) {
                                    for (i in 0 until c) sBuf[i] = (sBuf[i].toInt() xor k[((total + i) % k.size).toInt()].toInt()).toByte()
                                    output.write(sBuf, 0, c); total += c
                                }
                            } else {
                                println("[MovieKing v69] WARNING: Key Solver Failed. Sending Raw Data.")
                                output.write(buffer, 0, read)
                                var c: Int; while (stream.read(buffer).also { c = it } != -1) output.write(buffer, 0, c)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v69] Proxy Error: $e") }
        }

        private fun findKeyAndOffsetWithDebug(data: ByteArray, len: Int) {
            println("[MovieKing v69] Starting XOR-Solver (4-byte pattern scan)...")
            for (off in 0..2048) { // 탐색 범위 2KB로 확장
                // TS 헤더 [47 40 00 10]를 타겟으로 키 역산
                val k0 = data[off].toInt() xor 0x47
                val k1 = data[off + 1].toInt() xor 0x40 
                val k2 = data[off + 2].toInt() xor 0x00
                val k3 = data[off + 3].toInt() xor 0x10
                
                val key = byteArrayOf(k0.toByte(), k1.toByte(), k2.toByte(), k3.toByte())
                
                // 188바이트 간격으로 10번 연속 0x47이 나오는지 수학적 검증
                var matches = 0
                for (p in 0..9) {
                    val pos = off + (p * 188)
                    if (pos >= len) break
                    // Data[pos] ^ Key[pos % 4] == 0x47 인지 확인
                    // 여기서는 Key[0]가 항상 Data[off + p*188]과 만나야 함 (188이 4의 배수이므로)
                    if ((data[pos].toInt() xor k0).toByte() == 0x47.toByte()) matches++
                }
                
                // 디버깅: 5개 이상 맞으면 "거의 정답"으로 간주하고 상세 로그 출력
                if (matches >= 5) {
                    println("[MovieKing v69] Candidate found at Offset $off | Matches: $matches/10 | Key: ${key.joinToString("") { "%02X".format(it) }}")
                }

                if (matches >= 9) {
                    println("[MovieKing v69] >>> MATH SOLVED! <<<")
                    println("[MovieKing v69] Valid Offset: $off")
                    println("[MovieKing v69] XOR Key (4-byte): ${key.joinToString(" "){ "%02X".format(it) }}")
                    solvedKey = key
                    solvedOffset = off
                    return
                }
                
                // 500 단위로 진행 상황 출력
                if (off % 500 == 0) println("[MovieKing v69] Scanning... current offset: $off")
            }
            println("[MovieKing v69] FATAL: Solver could not find any valid TS pattern in first 2KB.")
        }
    }
}
