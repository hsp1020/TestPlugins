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
        println("=== [MovieKing v63] getUrl Start (Omni-Channel Brute Force) ===")
        try {
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""").find(playlistRes)
            var rawKeyJson: String? = null
            if (keyMatch != null) rawKeyJson = app.get(keyMatch.groupValues[1], headers = baseHeaders).text

            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val port = proxyServer!!.updateSession(baseHeaders, rawKeyJson)
            val proxyBaseUrl = "http://127.0.0.1:$port"
            var m3u8Content = playlistRes
            if (keyMatch != null) m3u8Content = m3u8Content.replace(keyMatch.groupValues[1], "$proxyBaseUrl/key.bin")

            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) "$proxyBaseUrl/proxy?url=${URLEncoder.encode(if (line.startsWith("http")) line else "$m3u8Base$line", "UTF-8")}" else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "$proxyBaseUrl/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v63] Error: $e") }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var rawKeyJson: String? = null
        @Volatile private var currentPlaylist: String = ""
        
        @Volatile private var foundKey: ByteArray? = null
        @Volatile private var foundOffset: Int = -1
        @Volatile private var foundShift: Int = 0 

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>, j: String?) = port.also { currentHeaders = h; rawKeyJson = j; foundKey = null; foundOffset = -1 }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray() + currentPlaylist.toByteArray())
                } else if (path.contains("/key.bin")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray() + ByteArray(16))
                } else if (path.contains("/proxy")) {
                    val url = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    runBlocking {
                        val res = app.get(url, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val stream = BufferedInputStream(res.body.byteStream())
                            val buffer = ByteArray(65536) 
                            stream.mark(65536); val read = stream.read(buffer); stream.reset()

                            if (read > 0 && foundKey == null && rawKeyJson != null) {
                                val candidates = generateAllOmniCandidates(rawKeyJson!!)
                                println("[MovieKing v63] Probing ${candidates.size * 16} omni-combinations...")
                                
                                outer@for ((name, key) in candidates) {
                                    for (s in 0..15) {
                                        // 64KB 버퍼 전체 스캔으로 확대
                                        for (i in 0 until (read - 600).coerceAtLeast(0)) {
                                            if ((buffer[i].toInt() xor key[(i + s) % 16].toInt()).toByte() == 0x47.toByte()) {
                                                var m = 0; for (p in 1..3) {
                                                    val pos = i + (p * 188)
                                                    if (pos + 1 > read) break
                                                    if ((buffer[pos].toInt() xor key[(pos + s) % 16].toInt()).toByte() == 0x47.toByte()) m++
                                                }
                                                if (m >= 2) {
                                                    println("[MovieKing v63] SUCCESS! $name, Offset: $i, Shift: $s"); foundKey = key; foundOffset = i; foundShift = s; break@outer
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (foundKey != null) {
                                val k = foundKey!!; val off = foundOffset; val s = foundShift; var total = 0L
                                val dec = ByteArray(read); for (i in 0 until read) dec[i] = (buffer[i].toInt() xor k[(i + s) % 16].toInt()).toByte()
                                if (read > off) output.write(dec, off, read - off)
                                total = read.toLong()
                                val sBuf = ByteArray(16384); var c: Int
                                while (stream.read(sBuf).also { c = it } != -1) {
                                    for (i in 0 until c) sBuf[i] = (sBuf[i].toInt() xor k[((total + i + s) % 16).toInt()].toInt()).toByte()
                                    output.write(sBuf, 0, c); total += c
                                }
                            } else {
                                output.write(buffer, 0, read)
                                var c: Int; while (stream.read(buffer).also { count -> c = count } != -1) output.write(buffer, 0, c)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v63] Stream Error: $e") }
        }

        private fun generateAllOmniCandidates(json: String): List<Pair<String, ByteArray>> {
            val list = mutableListOf<Pair<String, ByteArray>>()
            try {
                val decoded = if (json.startsWith("{")) json else String(Base64.decode(json, Base64.DEFAULT))
                val encKeyStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1)?.replace("\\/", "/") ?: return list
                val sources = listOf("Str" to encKeyStr.toByteArray(), "B64" to try { Base64.decode(encKeyStr, Base64.DEFAULT) } catch(e:Exception) { null })
                val perms = listOf(listOf(0,1,2,3), listOf(0,1,3,2), listOf(0,2,1,3), listOf(0,2,3,1), listOf(0,3,1,2), listOf(0,3,2,1), listOf(1,0,2,3), listOf(1,0,3,2), listOf(1,2,0,3), listOf(1,2,3,0), listOf(1,3,0,2), listOf(1,3,2,0), listOf(2,0,1,3), listOf(2,0,3,1), listOf(2,1,0,3), listOf(2,1,3,0), listOf(2,3,0,1), listOf(2,3,1,0), listOf(3,0,1,2), listOf(3,0,2,1), listOf(3,1,0,2), listOf(3,1,2,0), listOf(3,2,0,1), listOf(3,2,1,0))

                for ((sLabel, source) in sources) {
                    if (source == null) continue
                    for (gap in 0..5) { // 노이즈 간격 전수 조사
                        for (startOff in 0..5) { // 시작 오프셋 전수 조사
                            val segments = mutableListOf<ByteArray>()
                            for (i in 0..3) {
                                val pos = startOff + i * (4 + gap)
                                if (pos + 4 <= source.size) segments.add(source.copyOfRange(pos, pos + 4))
                            }
                            if (segments.size == 4) {
                                for (p in perms) {
                                    val key = ByteArray(16)
                                    for (j in 0..3) System.arraycopy(segments[p[j]], 0, key, j * 4, 4)
                                    list.add("$sLabel-G$gap-O$startOff-P${p.joinToString("")}" to key)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
            return list
        }
    }
}
