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
 * [Version: v2026-02-14-BitInterleaveFix]
 * 1. Bit Interleave: 역연산 로직 수정 (perm[i]를 Source Index로 사용하여 원본 위치 i로 복구).
 * 2. 이를 통해 올바른 16바이트 키 생성 보장.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val VERSION = "v2026-02-14-BitInterleaveFix"
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
        println("[BunnyPoorCdn] Version: $VERSION")
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
            val response = app.get(
                url = cleanUrl,
                headers = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA),
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
                
                val proxy = ProxyWebServer(VERSION)
                proxy.start()
                proxy.updateSession(capturedHeaders!!, hexIv)
                proxyServer = proxy

                val proxyRoot = "http://127.0.0.1:${proxy.port}"
                val newLines = mutableListOf<String>()
                val seqMap = ConcurrentHashMap<String, Long>()
                var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
                
                val uri = URI(capturedUrl)
                val domain = "${uri.scheme}://${uri.host}"
                val parentUrl = capturedUrl.substringBeforeLast("/")

                for (line in m3u8Content.lines()) {
                    if (line.startsWith("#EXT-X-KEY")) continue
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        val segmentUrl = if (line.startsWith("http")) line else if (line.startsWith("/")) "$domain$line" else "$parentUrl/$line"
                        seqMap[segmentUrl] = currentSeq
                        newLines.add("$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}")
                        currentSeq++
                    } else { newLines.add(line) }
                }

                proxy.setPlaylist(newLines.joinToString("\n"))
                proxy.updateSeqMap(seqMap)
                
                val keyUrlMatch = Regex("""URI="([^"]+)"""").find(m3u8Content)
                if (keyUrlMatch != null) {
                    val kUrl = keyUrlMatch.groupValues[1]
                    proxy.setTargetKeyUrl(if (kUrl.startsWith("http")) kUrl else if (kUrl.startsWith("/")) "$domain$kUrl" else "$parentUrl/$kUrl")
                }

                callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                })
                return true
            } catch (e: Exception) { e.printStackTrace() }
        }
        return false
    }

    class ProxyWebServer(val version: String) {
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
            serverSocket = ServerSocket(0)
            port = serverSocket!!.localPort
            isRunning = true
            thread(isDaemon = true) { 
                while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } 
            }
        }

        fun stop() { isRunning = false; serverSocket?.close() }
        fun updateSession(h: Map<String, String>, iv: String?) { currentHeaders = h; playlistIv = iv }
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
                        println("[BunnyPoorCdn] Decoding with logic version: $version")
                        realKey = BunnyJsonDecryptor.decrypt(String(rawData))
                        if (realKey != null) println("[BunnyPoorCdn] Key Decrypted: ${realKey!!.take(4).joinToString(""){"%02x".format(it)}}...")
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
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    output.write(currentPlaylist.toByteArray())
                } else if (path.contains("/proxy")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    val seq = seqMap[targetUrl] ?: 0L
                    ensureKey()
                    runBlocking {
                        val segHeaders = currentHeaders.toMutableMap()
                        segHeaders["Accept"] = "*/*"
                        val res = app.get(targetUrl, headers = segHeaders)
                        if (res.isSuccessful) {
                            val data = res.body.bytes()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (realKey != null) {
                                val decrypted = decryptSegment(data, realKey!!, seq)
                                if (decrypted != null) {
                                    if(decrypted.size > 188 && decrypted[0] == 0x47.toByte()) {
                                        // Good
                                    } else {
                                        println("[BunnyPoorCdn] Sync Byte FAILED for Seq $seq")
                                    }
                                    output.write(decrypted)
                                } else {
                                    output.write(data)
                                }
                            } else { output.write(data) }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e:Exception){} }
        }

        private fun decryptSegment(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            return try {
                val iv = ByteArray(16)
                if (!playlistIv.isNullOrEmpty()) {
                     val hex = playlistIv!!.removePrefix("0x")
                     hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                } else { for (i in 0..7) iv[15 - i] = (seq shr (i * 8)).toByte() }
                
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }
    }

    object BunnyJsonDecryptor {
        private fun decodeB64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private var noiseL: IntArray = IntArray(0)

        fun decrypt(jsonStr: String): ByteArray? {
            try {
                val json = JSONObject(jsonStr)
                var data = decodeB64(json.getString("encrypted_key"))
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
                    when(layer.getString("name")) {
                        "final_encrypt" -> {
                            val mask = decodeB64(layer.getString("xor_mask"))
                            val padLen = layer.optInt("pad_len", 0)
                            for (j in data.indices) data[j] = data[j] xor mask[j % mask.size]
                            if (padLen > 0 && data.size > padLen) data = data.copyOfRange(0, data.size - padLen)
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
                                val l = lens.getInt(p)
                                val targetLen = noiseLensArray?.getInt(j) ?: l
                                if (offsets[p] + targetLen <= data.size) {
                                    buffer.write(data, offsets[p], targetLen)
                                }
                                collectedLens[j] = targetLen
                            }
                            noiseL = collectedLens
                            data = buffer.toByteArray()
                        }
                        "xor_chain" -> {
                            val initStr = layer.optString("init_key", "")
                            val init = initStr.toByteArray(Charsets.UTF_8)
                            val newData = data.clone()
                            for (j in newData.size - 1 downTo 1) newData[j] = newData[j] xor newData[j - 1]
                            if (init.isNotEmpty()) newData[0] = newData[0] xor init[0]
                            data = newData
                        }
                        "sbox" -> {
                            val inv = decodeB64(layer.getString("inverse_sbox"))
                            for (j in data.indices) data[j] = inv[data[j].toInt() and 0xFF]
                        }
                        "bit_rotate" -> {
                            val rots = layer.getJSONArray("rotations")
                            for (j in data.indices) {
                                val r = rots.getInt(j % rots.length())
                                val v = data[j].toInt() and 0xFF
                                data[j] = ((v ushr r) or (v shl (8 - r))).toByte()
                            }
                        }
                        "segment_noise" -> {
                            val perm = layer.getJSONArray("perm")
                            val inOffsets = IntArray(noiseL.size)
                            var acc = 0
                            for (k in noiseL.indices) { inOffsets[k] = acc; acc += noiseL[k] }
                            val result = ByteArray(perm.length())
                            for (j in 0 until perm.length()) {
                                // 셔플된 데이터의 j번째 청크를 가져옴
                                val offset = inOffsets[j]
                                // 이 청크는 원래 perm[j] 번째 위치에 있어야 함 (Dest Index)
                                if (offset < data.size) {
                                    result[perm.getInt(j)] = data[offset]
                                }
                            }
                            data = result
                        }
                        "bit_interleave" -> {
                            val perm = layer.getJSONArray("perm")
                            val result = ByteArray(16)
                            for (j in 0 until perm.length()) {
                                // 셔플된 데이터의 perm[j] 번째 비트를 가져옴 (Source)
                                val srcBitIdx = perm.getInt(j)
                                val srcByteIdx = srcBitIdx / 8
                                val srcBitPos = 7 - (srcBitIdx % 8)
                                val bitVal = (data[srcByteIdx].toInt() shr srcBitPos) and 1
                                
                                // 이 비트를 원본의 j 번째 위치에 놓음 (Dest)
                                val destByteIdx = j / 8
                                val destBitPos = 7 - (j % 8)
                                
                                if (bitVal == 1) {
                                    result[destByteIdx] = (result[destByteIdx].toInt() or (1 shl destBitPos)).toByte()
                                }
                            }
                            data = result
                        }
                    }
                }
                return data
            } catch (e: Exception) { return null }
        }
    }
}
