// v3.0
package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v2.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var server: ServerSocket? = null
        private var serverPort = 0
        private val m3u8Cache = mutableMapOf<String, String>()

        fun startServer() {
            if (server != null) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    server = ServerSocket(0).apply { serverPort = localPort }
                    while (true) {
                        val socket = server?.accept() ?: break
                        launch(Dispatchers.IO) {
                            handleSocket(socket)
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }

        private fun handleSocket(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size >= 2 && parts[0] == "GET") {
                    val id = parts[1].substringAfterLast("/")
                    val m3u8 = m3u8Cache[id]
                    if (m3u8 != null) {
                        val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                "Connection: close\r\n" +
                                "\r\n" + m3u8
                        out.write(response.toByteArray(charset("UTF-8")))
                    } else {
                        val response = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                        out.write(response.toByteArray(charset("UTF-8")))
                    }
                }
                out.flush()
            } catch (e: Exception) {
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    init {
        startServer()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val uri = URI(url)
            val hostUrl = "${uri.scheme}://${uri.host}"
            val baseHeaders = mutableMapOf("Referer" to "$hostUrl/", "Origin" to hostUrl, "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            
            val candidates = if (keyMatch != null) withContext(Dispatchers.Default) { solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1]) } else emptyList()
            
            val lines = playlistRes.lines()
            val currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            val firstSegmentUrl = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let { if (it.startsWith("http")) it else "${m3u8Url.substringBeforeLast("/")}/$it" }

            var confirmedKey = ByteArray(16)
            var confirmedIvType = -1

            if (firstSegmentUrl != null && candidates.isNotEmpty()) {
                try {
                    val firstSegBytes = app.get(firstSegmentUrl, headers = baseHeaders).body.bytes()
                    val ivs = getIvList(currentSeq, hexIv)
                    val checkSize = Math.min(firstSegBytes.size, 188 * 2)
                    
                    outer@ for ((keyIdx, key) in candidates.withIndex()) {
                        for ((ivIdx, iv) in ivs.withIndex()) {
                            try {
                                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                val head = cipher.update(firstSegBytes.take(checkSize).toByteArray())
                                if (head.isNotEmpty() && head[0] == 0x47.toByte() && head.size > 188 && head[188] == 0x47.toByte()) {
                                    confirmedKey = key
                                    confirmedIvType = ivIdx
                                    break@outer
                                }
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {}
            }

            val base64Key = Base64.encodeToString(confirmedKey, Base64.NO_WRAP)
            val dataUriKey = "data:application/octet-stream;base64,$base64Key"

            val newLines = mutableListOf<String>()
            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    var newKeyLine = """#EXT-X-KEY:METHOD=AES-128,URI="$dataUriKey""""
                    if (confirmedIvType == 0 && hexIv != null) newKeyLine += """,IV=$hexIv"""
                    else if (confirmedIvType == 2) newKeyLine += """,IV=0x00000000000000000000000000000000"""
                    newLines.add(newKeyLine)
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    val absoluteLine = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    newLines.add(absoluteLine)
                } else {
                    newLines.add(line)
                }
            }
            
            val finalM3u8 = newLines.joinToString("\n")
            val cacheId = UUID.randomUUID().toString()
            m3u8Cache[cacheId] = finalM3u8
            val proxyUrl = "http://127.0.0.1:$serverPort/$cacheId"
            
            callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) { this.referer = "$hostUrl/" })
        } catch (e: Exception) {}
    }

    private fun getIvList(seq: Long, hexIv: String?): List<ByteArray> {
        val ivs = mutableListOf<ByteArray>()
        if (!hexIv.isNullOrEmpty()) {
            try {
                val hex = hexIv.removePrefix("0x")
                val iv = ByteArray(16)
                hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                ivs.add(iv)
            } catch(e:Exception) { ivs.add(ByteArray(16)) }
        } else ivs.add(ByteArray(16))
        
        val seqIv = ByteArray(16)
        for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
        ivs.add(seqIv)
        ivs.add(ByteArray(16))
        return ivs
    }

    private suspend fun solveKeyCandidatesCombinatorial(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }

            if (b64.size == 16) list.add(b64)
            if (b64.size >= 22) { 
                val src = b64
                val targetGapsList = listOf(listOf(0, 2, 2, 2, 2), listOf(0, 1, 1, 1, 1))
                val allPerms = generatePermutations(listOf(0, 1, 2, 3))

                for (gaps in targetGapsList) {
                    try {
                        val segs = mutableListOf<ByteArray>()
                        var idx = gaps[0]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[1]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[2]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[3]
                        segs.add(src.copyOfRange(idx, idx + 4))

                        for (perm in allPerms) {
                            val k = ByteArray(16)
                            for (j in 0 until 4) System.arraycopy(segs[perm[j]], 0, k, j * 4, 4)
                            list.add(k)
                        }
                    } catch (e: Exception) {}
                }
            }
            return list.distinctBy { it.contentHashCode() }
        } catch (e: Exception) { return emptyList() }
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
}
