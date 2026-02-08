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
        println("=== [MovieKing v72] getUrl Start (1MB Super Deep Scan) ===")
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
            
            val port = proxyServer!!.updateSession(baseHeaders)
            val proxyBaseUrl = "http://127.0.0.1:$port"
            
            // AES 태그 제거로 플레이어 간섭 차단
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
        } catch (e: Exception) { println("[MovieKing v72] Error: $e") }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentPlaylist: String = ""
        
        // 정답 공유 변수
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
                    output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray() + currentPlaylist.toByteArray())
                } else if (path.contains("/proxy")) {
                    val url = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    runBlocking {
                        val res = app.get(url, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val stream = BufferedInputStream(res.body.byteStream())
                            
                            // [수정] 1MB 탐색을 위해 1.2MB 버퍼 할당
                            val probeBufferSize = 1200 * 1024
                            val buffer = ByteArray(probeBufferSize)
                            stream.mark(probeBufferSize)
                            val read = stream.read(buffer)
                            stream.reset()

                            // [수학적 해결사] 1MB 슈퍼 딥 스캔
                            if (read > 0 && solvedKey == null) findKeySuperDeepScan(buffer, read)

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (solvedKey != null) {
                                val k = solvedKey!!; val off = solvedOffset
                                val initialDec = ByteArray(read)
                                for (i in 0 until read) initialDec[i] = (buffer[i].toInt() xor k[(i % k.size)].toInt()).toByte()
                                
                                if (read > off) output.write(initialDec, off, read - off)
                                
                                var total = read.toLong()
                                val sBuf = ByteArray(32768) // 스트리밍은 32KB씩 고속 처리
                                var c: Int
                                while (stream.read(sBuf).also { c = it } != -1) {
                                    for (i in 0 until c) sBuf[i] = (sBuf[i].toInt() xor k[((total + i) % k.size).toInt()].toInt()).toByte()
                                    output.write(sBuf, 0, c); total += c
                                }
                            } else {
                                // 실패 시 원본 전송 (보통 발생 안 함)
                                output.write(buffer, 0, read)
                                var c: Int; while (stream.read(buffer).also { count -> c = count } != -1) output.write(buffer, 0, c)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v72] Stream Error: $e") }
        }

        private fun findKeySuperDeepScan(data: ByteArray, len: Int) {
            println("[MovieKing v72] Starting 1MB Super-Deep Scan for TS Sync...")
            val scanLimit = (1024 * 1024).coerceAtMost(len - 2000) // 최대 1MB 또는 데이터 끝까지
            
            for (off in 0..scanLimit) {
                // TS 헤더 [47 40 00 10] 기반 4바이트 키 후보 생성
                val k0 = data[off].toInt() xor 0x47
                // 검증: 188바이트 간격으로 10개 패킷 헤더 확인
                var m = 0
                for (p in 0..9) {
                    val pos = off + (p * 188)
                    if (pos >= len) break
                    if ((data[pos].toInt() xor k0).toByte() == 0x47.toByte()) m++
                }
                
                if (m >= 9) {
                    val k1 = data[off+1].toInt() xor 0x40
                    val k2 = data[off+2].toInt() xor 0x00
                    val k3 = data[off+3].toInt() xor 0x10
                    val finalKey = byteArrayOf(k0.toByte(), k1.toByte(), k2.toByte(), k3.toByte())
                    
                    println("[MovieKing v72] !!! JACKPOT !!!")
                    println("[MovieKing v72] Found Sync at Offset: $off")
                    println("[MovieKing v72] Calculated XOR Key: ${finalKey.joinToString(""){"%02X".format(it)}}")
                    
                    solvedKey = finalKey
                    solvedOffset = off
                    return
                }
                
                // 100KB 마다 진행 상황 로깅
                if (off > 0 && off % (100 * 1024) == 0) {
                    println("[MovieKing v72] Scanning... ${off / 1024}KB processed.")
                }
            }
            println("[MovieKing v72] FATAL: Could not find TS Sync in 1MB range.")
        }
    }
}
