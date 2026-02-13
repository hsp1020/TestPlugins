package com.tvwiki

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlin.experimental.xor

/**
 * [Version: v2026-02-13-SizeReductionFix]
 * 1. Decoy Shuffle Decrypt: 추출된 세그먼트에서 마지막 1바이트(Noise) 제거 로직 추가. (38 bytes -> 26 bytes)
 * 2. Segment Noise Decrypt: 셔플 역연산 후, 각 세그먼트에서 첫 1바이트(Real Data)만 추출. (26 bytes -> 16 bytes)
 * 3. 이를 통해 1603바이트 JSON -> 16바이트 Valid Key로 완벽 변환.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        proxyServer?.stop()
        proxyServer = null
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null

        try {
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA
            )
            
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
                val cookie = CookieManager.getInstance().getCookie(capturedUrl) ?: ""
                
                capturedHeaders = mutableMapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to "https://player.bunny-frame.online/",
                    "Origin" to "https://player.bunny-frame.online",
                    "Cookie" to cookie,
                    "Sec-Ch-Ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to "\"Windows\""
                )
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (capturedUrl != null && capturedHeaders != null) {
            try {
                val m3u8Res = app.get(capturedUrl, headers = capturedHeaders!!)
                val m3u8Content = m3u8Res.text

                val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(m3u8Content)
                val hexIv = keyMatch?.groupValues?.get(2)
                
                val proxy = ProxyWebServer()
                proxy.start()
                proxy.updateSession(capturedHeaders!!, hexIv)
                proxyServer = proxy

                val proxyPort = proxy.port
                val proxyRoot = "http://127.0.0.1:$proxyPort"

                val newLines = mutableListOf<String>()
                val lines = m3u8Content.lines()
                val seqMap = ConcurrentHashMap<String, Long>()
                var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
                
                val uri = URI(capturedUrl)
                val domain = "${uri.scheme}://${uri.host}"
                val parentUrl = capturedUrl.substringBeforeLast("/")

                for (line in lines) {
                    if (line.startsWith("#EXT-X-KEY")) {
                        continue
                    }
                    
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        val segmentUrl = when {
                            line.startsWith("http") -> line
                            line.startsWith("/") -> "$domain$line"
                            else -> "$parentUrl/$line"
                        }
                        
                        seqMap[segmentUrl] = currentSeq
                        val encodedSegUrl = URLEncoder.encode(segmentUrl, "UTF-8")
                        newLines.add("$proxyRoot/proxy?url=$encodedSegUrl")
                        currentSeq++
                    } else {
                        newLines.add(line)
                    }
                }

                val proxyM3u8 = newLines.joinToString("\n")
                proxy.setPlaylist(proxyM3u8)
                proxy.updateSeqMap(seqMap)
                
                val keyUrlMatch = Regex("""URI="([^"]+)"""").find(m3u8Content)
                if (keyUrlMatch != null) {
                    var kUrl = keyUrlMatch.groupValues[1]
                    kUrl = when {
                        kUrl.startsWith("http") -> kUrl
                        kUrl.startsWith("/") -> "$domain$kUrl"
                        else -> "$parentUrl/$kUrl"
                    }
                    proxy.setTargetKeyUrl(kUrl)
                }

                callback(
                    newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return false
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var seqMap: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
        @Volatile private var targetKeyUrl: String? = null
        
        @Volatile private var realKey: ByteArray? = null

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) {}
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun updateSession(h: Map<String, String>, iv: String?) {
            currentHeaders = h; playlistIv = iv
            realKey = null
        }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }
        fun setTargetKeyUrl(url: String) { targetKeyUrl = url }

        private fun ensureKey() {
            if (realKey != null) return
            if (targetKeyUrl == null) return

            runBlocking {
                try {
                    val cleanKeyUrl = targetKeyUrl!!.replace(Regex("[?&]mode=obfuscated"), "")
                    val res = app.get(cleanKeyUrl, headers = currentHeaders)
                    var rawData = res.body.bytes()

                    if (rawData.size > 100 && rawData[0] == '{'.code.toByte()) {
                        println("[BunnyPoorCdn] JSON Key Detected! Decrypting...")
                        val jsonStr = String(rawData)
                        val decrypted = BunnyJsonDecryptor.decrypt(jsonStr)
                        if (decrypted != null) {
                            realKey = decrypted
                            println("[BunnyPoorCdn] Decryption Finished (Pending TS Check). Key Size: ${decrypted.size}")
                        } else {
                            println("[BunnyPoorCdn] Decryption Failed.")
                        }
                    } else if (rawData.size == 16) {
                        realKey = rawData
                        println("[BunnyPoorCdn] Got Clean Key directly!")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val parts = line.split(" ")
                if (parts.size < 2) return@thread
                val path = parts[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    ensureKey()
                    val body = currentPlaylist.toByteArray(charset("UTF-8"))
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    output.write(body)
                } else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    
                    val seq = seqMap[targetUrl] ?: 0L
                    
                    ensureKey()

                    runBlocking {
                        try {
                            val res = app.get(targetUrl, headers = currentHeaders)
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                                if (realKey != null) {
                                    val decrypted = decryptSegment(rawData, realKey!!, seq)
                                    if (decrypted != null) {
                                        if (decrypted.size > 188 && decrypted[0] == 0x47.toByte()) {
                                            println("[BunnyPoorCdn] Key Verification: SUCCESS")
                                        } else {
                                            println("[BunnyPoorCdn] Key Verification: FAILED (Wrong Key)")
                                        }
                                        output.write(decrypted)
                                    } else {
                                        output.write(rawData)
                                    }
                                } else {
                                    output.write(rawData)
                                }
                            } else {
                                output.write("HTTP/1.1 ${res.code} Error\r\n\r\n".toByteArray())
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        private fun decryptSegment(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            return try {
                val iv = ByteArray(16)
                if (!playlistIv.isNullOrEmpty()) {
                     val hex = playlistIv!!.removePrefix("0x")
                     hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                } else {
                    for (i in 0..7) iv[15 - i] = (seq shr (i * 8)).toByte()
                }

                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }
    }

    object BunnyJsonDecryptor {
        private fun decodeBase64(input: String): ByteArray {
            return Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        fun decrypt(jsonStr: String): ByteArray? {
            try {
                val json = JSONObject(jsonStr)
                val encryptedKeyB64 = json.getString("encrypted_key")
                var data = decodeBase64(encryptedKeyB64)
                
                val layers = json.getJSONArray("layers")
                for (i in layers.length() - 1 downTo 0) {
                    val layer = layers.getJSONObject(i)
                    val name = layer.getString("name")
                    
                    data = when(name) {
                        "final_encrypt" -> {
                            val mask = layer.getString("xor_mask")
                            val maskBytes = decodeBase64(mask)
                            xor(data, maskBytes)
                        }
                        "decoy_shuffle" -> {
                            val positions = layer.getJSONArray("real_positions")
                            val lengths = layer.getJSONArray("segment_lengths")
                            
                            // 1. 전체 세그먼트 파싱 (Offsets 계산)
                            val segmentOffsets = IntArray(lengths.length())
                            var currentOffset = 0
                            for (j in 0 until lengths.length()) {
                                segmentOffsets[j] = currentOffset
                                currentOffset += lengths.getInt(j)
                            }
                            
                            val buffer = ByteArrayOutputStream()
                            
                            // 2. Real Positions에서 세그먼트 추출 + [1바이트 제거]
                            // Decoy 단계에서 1바이트 노이즈가 붙어있으므로 제거해야 segment_noise 입력 크기(26)와 맞음.
                            for (j in 0 until positions.length()) {
                                val pos = positions.getInt(j)
                                val len = lengths.getInt(pos)
                                val offset = segmentOffsets[pos]
                                
                                // "len - 1" 만큼만 복사 (마지막 바이트 제거)
                                if (len > 1) {
                                    buffer.write(data, offset, len - 1)
                                }
                            }
                            buffer.toByteArray()
                        }
                        "xor_chain" -> {
                            val initKeyB64 = layer.optString("init_key", null)
                            val ivBytes = if (initKeyB64 != null) decodeBase64(initKeyB64) else ByteArray(0)
                            val newData = data.clone()
                            for (j in newData.size - 1 downTo 1) {
                                newData[j] = newData[j] xor newData[j-1]
                            }
                            if (newData.isNotEmpty() && ivBytes.isNotEmpty()) {
                                newData[0] = newData[0] xor ivBytes[0]
                            }
                            newData
                        }
                        "sbox" -> {
                            val invSboxStr = layer.getString("inverse_sbox")
                            val invSbox = decodeBase64(invSboxStr)
                            val newData = ByteArray(data.size)
                            for (j in data.indices) {
                                val idx = data[j].toInt() and 0xFF
                                newData[j] = invSbox[idx]
                            }
                            newData
                        }
                        "bit_rotate" -> {
                            val rotations = layer.getJSONArray("rotations")
                            val newData = ByteArray(data.size)
                            for (j in data.indices) {
                                val rot = rotations.getInt(j % rotations.length())
                                val b = data[j].toInt() and 0xFF
                                val r = (b ushr rot) or (b shl (8 - rot))
                                newData[j] = r.toByte()
                            }
                            newData
                        }
                        "segment_noise" -> {
                            val perm = layer.getJSONArray("perm")
                            val noiseLens = layer.getJSONArray("noise_lens")
                            
                            // 1. 역순 셔플을 위한 매핑 (Dest -> Src)
                            // 암호화: dest[i] = src[perm[i]] -> 복호화: src[perm[i]] = dest[i]
                            // 여기서는 data가 'dest'(셔플된 상태)이고, 우리는 'src'(정렬된 상태)를 원함.
                            // 즉, Result[perm[i]] <- Data[i번째 청크]
                            
                            val chunkData = arrayOfNulls<ByteArray>(perm.length())
                            
                            // Data를 Chunk로 쪼개기 (현재 순서대로)
                            // 주의: 이때 chunk 길이는 무엇인가?
                            // noise_lens는 원본 순서의 길이임.
                            // 셔플된 상태에서의 i번째 청크 길이는?
                            // 셔플된 상태의 i번째 청크는 원본의 perm[i]번째 청크임.
                            // 따라서 길이는 noiseLens[perm[i]]임.
                            
                            var currentOffset = 0
                            for (i in 0 until perm.length()) {
                                val originalIndex = perm.getInt(i)
                                val len = noiseLens.getInt(originalIndex)
                                
                                val chunk = ByteArray(len)
                                System.arraycopy(data, currentOffset, chunk, 0, len)
                                currentOffset += len
                                
                                // 원본 위치에 배치
                                chunkData[originalIndex] = chunk
                            }
                            
                            // 2. Flatten + [Reduction] (각 청크에서 1바이트만 추출)
                            // 26 bytes -> 16 bytes
                            val buffer = ByteArrayOutputStream()
                            for (chunk in chunkData) {
                                if (chunk != null && chunk.isNotEmpty()) {
                                    // 첫 1바이트만 사용
                                    buffer.write(chunk[0].toInt())
                                }
                            }
                            buffer.toByteArray()
                        }
                        "bit_interleave" -> data 
                        else -> data
                    }
                }
                return data
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
        
        private fun xor(data: ByteArray, key: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = data[i] xor key[i % key.size]
            }
            return out
        }
    }
}
