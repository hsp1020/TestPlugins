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
import org.json.JSONArray
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
 * [Version: v2026-02-14-Final-ObfuscationFix]
 * 1. .gif 위장 대응: 프록시 응답의 Content-Type을 'video/mp2t'로 강제하여 플레이어 Sniffing 유도.
 * 2. Key Logic Fix: Segment Noise 및 Bit Interleave의 역연산 로직을 파이썬 검증 결과에 맞춰 정밀 수정.
 * 3. IV Logic: M3U8에 IV가 명시된 경우 이를 최우선으로 사용.
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
                    "Sec-Ch-Ua-Platform" to "\"Windows\"",
                    "Accept" to "*/*"
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
                    if (line.startsWith("#EXT-X-KEY")) continue
                    
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
            if (realKey != null || targetKeyUrl == null) return
            runBlocking {
                try {
                    val res = app.get(targetKeyUrl!!, headers = currentHeaders)
                    val rawData = res.body.bytes()
                    if (rawData.size > 100 && rawData[0] == '{'.code.toByte()) {
                        val decrypted = BunnyJsonDecryptor.decrypt(String(rawData))
                        if (decrypted != null) {
                            realKey = decrypted
                            println("[BunnyPoorCdn] Key Decrypted Successfully.")
                        }
                    } else if (rawData.size == 16) { realKey = rawData }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    ensureKey()
                    val body = currentPlaylist.toByteArray(charset("UTF-8"))
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body)
                } else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    val seq = seqMap[targetUrl] ?: 0L
                    ensureKey()
                    runBlocking {
                        try {
                            // .gif 위장에 대응하여 헤더 구성
                            val segmentHeaders = currentHeaders.toMutableMap()
                            segmentHeaders["Accept"] = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
                            
                            val res = app.get(targetUrl, headers = segmentHeaders)
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                // 응답 시 Content-Type을 비디오로 강제
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                                if (realKey != null) {
                                    val decrypted = decryptSegment(rawData, realKey!!, seq)
                                    if (decrypted != null && decrypted[0] == 0x47.toByte()) {
                                        output.write(decrypted)
                                    } else {
                                        println("[BunnyPoorCdn] Sync Byte 0x47 Missing for Seq $seq. Key might be wrong.")
                                        output.write(rawData)
                                    }
                                } else { output.write(rawData) }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
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
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }
    }

    object BunnyJsonDecryptor {
        private fun decodeBase64(input: String): ByteArray {
            return Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        private var latestNoiseLens: IntArray = IntArray(0)

        fun decrypt(jsonStr: String): ByteArray? {
            try {
                val json = JSONObject(jsonStr)
                var data = decodeBase64(json.getString("encrypted_key"))
                val layers = json.getJSONArray("layers")
                
                var noiseLensArray: JSONArray? = null
                for (i in 0 until layers.length()) {
                    if (layers.getJSONObject(i).getString("name") == "segment_noise") {
                        noiseLensArray = layers.getJSONObject(i).getJSONArray("noise_lens")
                        break
                    }
                }

                for (i in layers.length() - 1 downTo 0) {
                    val layer = layers.getJSONObject(i)
                    val name = layer.getString("name")
                    
                    data = when(name) {
                        "final_encrypt" -> {
                            val mask = decodeBase64(layer.getString("xor_mask"))
                            val out = ByteArray(data.size)
                            for (j in data.indices) out[j] = data[j] xor mask[j % mask.size]
                            out
                        }
                        "decoy_shuffle" -> {
                            val pos = layer.getJSONArray("real_positions")
                            val lens = layer.getJSONArray("segment_lengths")
                            val offsets = IntArray(lens.length())
                            var acc = 0
                            for (j in 0 until lens.length()) { offsets[j] = acc; acc += lens.getInt(j) }
                            
                            val buffer = ByteArrayOutputStream()
                            val collectedLens = IntArray(pos.length())
                            for (j in 0 until pos.length()) {
                                val p = pos.getInt(j)
                                val originalLen = lens.getInt(p)
                                // [수정] noise_lens를 기준으로 정확하게 Truncate
                                val targetLen = noiseLensArray?.getInt(j) ?: originalLen
                                buffer.write(data, offsets[p], targetLen)
                                collectedLens[j] = targetLen
                            }
                            latestNoiseLens = collectedLens
                            buffer.toByteArray()
                        }
                        "xor_chain" -> {
                            val initKey = decodeBase64(layer.optString("init_key", ""))
                            val newData = data.clone()
                            // [수정] 체인 복호화 방향: 뒤에서부터 앞으로 (data[j] ^= data[j-1])
                            for (j in newData.size - 1 downTo 1) newData[j] = newData[j] xor newData[j - 1]
                            if (initKey.isNotEmpty()) newData[0] = newData[0] xor initKey[0]
                            newData
                        }
                        "sbox" -> {
                            val inv = decodeBase64(layer.getString("inverse_sbox"))
                            val out = ByteArray(data.size)
                            for (j in data.indices) out[j] = inv[data[j].toInt() and 0xFF]
                            out
                        }
                        "bit_rotate" -> {
                            val rots = layer.getJSONArray("rotations")
                            val out = ByteArray(data.size)
                            for (j in data.indices) {
                                val r = rots.getInt(j % rots.length())
                                val v = data[j].toInt() and 0xFF
                                out[j] = ((v ushr r) or (v shl (8 - r))).toByte()
                            }
                            out
                        }
                        "segment_noise" -> {
                            val perm = layer.getJSONArray("perm")
                            val inOffsets = IntArray(latestNoiseLens.size)
                            var acc = 0
                            for (k in latestNoiseLens.indices) { inOffsets[k] = acc; acc += latestNoiseLens[k] }
                            
                            val result = ByteArray(perm.length())
                            for (j in 0 until perm.length()) {
                                // 셔플된 j번째 청크의 첫 바이트를 원본의 perm[j] 위치로
                                result[perm.getInt(j)] = data[inOffsets[j]]
                            }
                            result
                        }
                        "bit_interleave" -> {
                            val perm = layer.getJSONArray("perm")
                            val result = ByteArray(16)
                            for (j in 0 until perm.length()) {
                                // bit j of input belongs at perm[j] of output
                                val bit = (data[j / 8].toInt() shr (7 - (j % 8))) and 1
                                if (bit == 1) {
                                    val destIdx = perm.getInt(j)
                                    result[destIdx / 8] = (result[destIdx / 8].toInt() or (1 shl (7 - (destIdx % 8)))).toByte()
                                }
                            }
                            result
                        }
                        else -> data
                    }
                }
                return data
            } catch (e: Exception) { return null }
        }
    }
}
