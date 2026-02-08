package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InputStream
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("=== [MovieKing v59] getUrl Start (Build Fix + Brute Force) ===")
        try {
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8UrlMatch = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
            val m3u8Url = m3u8UrlMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""").find(playlistRes)
            
            var rawKeyJson: String? = null
            if (keyMatch != null) {
                rawKeyJson = app.get(keyMatch.groupValues[1], headers = baseHeaders).text
            }

            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            val port = proxyServer!!.updateSession(baseHeaders, rawKeyJson)
            val proxyBaseUrl = "http://127.0.0.1:$port"
            
            var m3u8Content = playlistRes
            if (keyMatch != null) {
                m3u8Content = m3u8Content.replace(keyMatch.groupValues[1], "$proxyBaseUrl/key.bin")
            }

            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyBaseUrl/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            
            callback(
                newExtractorLink(name, name, "$proxyBaseUrl/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                }
            )
        } catch (e: Exception) {
            println("[MovieKing v59] Error: $e")
        }
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

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        
        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                thread(isDaemon = true) {
                    while (isAlive()) {
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) { isRunning = false }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close() } catch (e: Exception) {}
        }

        fun updateSession(h: Map<String, String>, j: String?) = port.also { 
            currentHeaders = h; rawKeyJson = j; foundKey = null; foundOffset = -1 
        }

        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    val data = currentPlaylist.toByteArray()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray())
                    output.write(data)
                } else if (path.contains("/key.bin")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\n\r\n".toByteArray())
                    output.write(ByteArray(16))
                } else if (path.contains("/proxy")) {
                    val url = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    runBlocking {
                        val res = app.get(url, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val stream = BufferedInputStream(res.body.byteStream())
                            val buffer = ByteArray(65536) 
                            stream.mark(65536)
                            val read = stream.read(buffer)
                            stream.reset()

                            if (read > 0 && foundKey == null && rawKeyJson != null) {
                                val candidates = generateAllCombinations(rawKeyJson!!)
                                println("[MovieKing v59] Probing ${candidates.size} combinations...")
                                
                                outer@for ((name, key) in candidates) {
                                    val dec = ByteArray(read)
                                    for (i in 0 until read) dec[i] = (buffer[i].toInt() xor key[i % 16].toInt()).toByte()
                                    for (i in 0 until read - 376) {
                                        if (dec[i] == 0x47.toByte() && dec[i+188] == 0x47.toByte() && dec[i+376] == 0x47.toByte()) {
                                            println("[MovieKing v59] SUCCESS: $name at offset $i")
                                            foundKey = key; foundOffset = i; break@outer
                                        }
                                    }
                                }
                            }

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (foundKey != null) {
                                val k = foundKey!!; val off = foundOffset
                                val initialDec = ByteArray(read)
                                for (i in 0 until read) initialDec[i] = (buffer[i].toInt() xor k[i % 16].toInt()).toByte()
                                if (read > off) output.write(initialDec, off, read - off)
                                
                                var total = read.toLong()
                                var count: Int
                                val streamBuffer = ByteArray(8192)
                                while (stream.read(streamBuffer).also { count = it } != -1) {
                                    for (i in 0 until count) {
                                        streamBuffer[i] = (streamBuffer[i].toInt() xor k[((total + i) % 16).toInt()].toInt()).toByte()
                                    }
                                    output.write(streamBuffer, 0, count)
                                    total += count
                                }
                            } else {
                                output.write(buffer, 0, read)
                                var count: Int; while (stream.read(buffer).also { count = it } != -1) output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                output.flush()
                socket.close()
            } catch (e: Exception) { println("[MovieKing v59] Stream Error: $e") }
        }

        private fun generateAllCombinations(json: String): List<Pair<String, ByteArray>> {
            val list = mutableListOf<Pair<String, ByteArray>>()
            try {
                val decoded = if (json.startsWith("{")) json else String(Base64.decode(json, Base64.DEFAULT))
                val encKey = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decoded)?.groupValues?.get(1)?.replace("\\/", "/") ?: return list
                val perms = listOf(listOf(0,1,2,3), listOf(0,1,3,2), listOf(0,2,1,3), listOf(0,2,3,1), listOf(0,3,1,2), listOf(0,3,2,1), listOf(1,0,2,3), listOf(1,0,3,2), listOf(1,2,0,3), listOf(1,2,3,0), listOf(1,3,0,2), listOf(1,3,2,0), listOf(2,0,1,3), listOf(2,0,3,1), listOf(2,1,0,3), listOf(2,1,3,0), listOf(2,3,0,1), listOf(2,3,1,0), listOf(3,0,1,2), listOf(3,0,2,1), listOf(3,1,0,2), listOf(3,1,2,0), listOf(3,2,0,1), listOf(3,2,1,0))
                
                // Behind
                val segsB = mutableListOf<ByteArray>()
                for(i in 0 until 4) segsB.add(encKey.substring(i*6, i*6+4).toByteArray())
                for(p in perms) list.add("Behind-${p.joinToString("")}" to assemble(segsB, p))

                // Front
                val segsF = mutableListOf<ByteArray>()
                for(i in 0 until 4) segsF.add(encKey.substring(i*6+2, i*6+6).toByteArray())
                for(p in perms) list.add("Front-${p.joinToString("")}" to assemble(segsF, p))
            } catch (e: Exception) {}
            return list
        }
        private fun assemble(s: List<ByteArray>, p: List<Int>) = ByteArray(16).apply { var o = 0; for(i in p) { System.arraycopy(s[i], 0, this, o, 4); o+=4 } }
    }
}
