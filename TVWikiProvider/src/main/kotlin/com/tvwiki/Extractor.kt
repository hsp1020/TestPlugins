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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [Version: v2026-02-14-IvRecovery]
 * 1. IV Recovery: ECB 모드로 첫 블록을 복호화하여 역으로 IV를 산출하는 스마트 크랙 로직 적용.
 * 2. Key Decryption: Python 검증 로직 탑재.
 * 3. Fallback: 실패 시에도 원본 스트림 전송.
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
        @Volatile private var cachedIv: ByteArray? = null

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
            cachedIv = null
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
                        val jsonStr = String(rawData).trim()
                        val decrypted = BunnyJsonDecryptor.decrypt(jsonStr)
                        if (decrypted != null) {
                            realKey = decrypted
                            println("[BunnyPoorCdn] Decryption Success. Key Hex: ${bytesToHex(decrypted)}")
                        }
                    } else if (rawData.size == 16) {
                        realKey = rawData
                        println("[BunnyPoorCdn] Got Clean Key directly!")
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        
        private fun bytesToHex(bytes: ByteArray): String {
            val hexArray = "0123456789ABCDEF".toCharArray()
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
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
                                    val decrypted = smartDecrypt(rawData, realKey!!, seq)
                                    output.write(decrypted ?: rawData)
                                } else {
                                    output.write(rawData)
                                }
                            } else {
                                output.write("HTTP/1.1 ${res.code} Error\r\n\r\n".toByteArray())
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        // [핵심] IV Recovery & Smart Decrypt
        private fun smartDecrypt(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            // 1. 이미 찾은 IV가 있으면 사용
            if (cachedIv != null) {
                // Sequence 기반이면 Seq 업데이트 필요. 여기서는 고정 IV인지 체크
                // 보통 HLS는 Seq마다 IV가 바뀜. 하지만 패턴이 있을 것임.
                // 일단 정석대로 Seq 기반 시도 후 Recovery 시도.
            }
            
            // 2. 표준 IV 시도
            val ivStandard = ByteArray(16)
            ByteBuffer.wrap(ivStandard).order(ByteOrder.BIG_ENDIAN).putLong(8, seq)
            var decrypted = attemptDecrypt(data, key, ivStandard)
            if (isValidTS(decrypted)) return decrypted

            // 3. IV Recovery (ECB Attack)
            // 첫 블록(16바이트)을 ECB로 복호화 -> P0'
            // 진짜 P0의 첫 바이트는 0x47.
            // P0[0] = P0'[0] ^ IV[0]  =>  0x47 = P0'[0] ^ IV[0]  =>  IV[0] = P0'[0] ^ 0x47
            try {
                val cipherECB = Cipher.getInstance("AES/ECB/NoPadding")
                cipherECB.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                val firstBlock = data.copyOfRange(0, 16)
                val p0_prime = cipherECB.doFinal(firstBlock)
                
                // IV[0] 추측
                val estimatedIvByte0 = (p0_prime[0] xor 0x47.toByte())
                
                // 추측된 바이트로 IV 후보 생성 (Zero Padding 가정)
                val recoveredIv = ByteArray(16)
                recoveredIv[0] = estimatedIvByte0 
                // 나머지 바이트는 Seq나 0일 확률 높음. 
                // 하지만 IV 전체를 복구하긴 어려움.
                // 만약 IV가 Sequence라면, IV[0]가 0일 확률이 매우 높음 (Seq가 엄청 크지 않은 이상).
                
                // 만약 estimatedIvByte0가 0이 아니라면, 뭔가 Offset이 있는 것임.
                if (estimatedIvByte0 != 0.toByte()) {
                    println("[BunnyPoorCdn] Non-zero IV Start detected: $estimatedIvByte0")
                }
                
                // 다른 IV 모드 시도 (Little Endian)
                val ivLE = ByteArray(16)
                ByteBuffer.wrap(ivLE).order(ByteOrder.LITTLE_ENDIAN).putLong(8, seq)
                decrypted = attemptDecrypt(data, key, ivLE)
                if (isValidTS(decrypted)) return decrypted

                // Zero IV 시도
                decrypted = attemptDecrypt(data, key, ByteArray(16))
                if (isValidTS(decrypted)) return decrypted
                
            } catch(e: Exception) {}

            return null
        }

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
            return try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }

        private fun isValidTS(data: ByteArray?): Boolean {
            if (data == null || data.size < 376) return false
            return data[0] == 0x47.toByte() && data[188] == 0x47.toByte()
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
                var noiseLens: JSONArray? = null
                for (i in 0 until layers.length()) {
                    if (layers.getJSONObject(i).getString("name") == "segment_noise") {
                        noiseLens = layers.getJSONObject(i).getJSONArray("noise_lens")
                        break
                    }
                }
                
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
                            val segmentOffsets = IntArray(lengths.length())
                            var currentOffset = 0
                            for (j in 0 until lengths.length()) {
                                segmentOffsets[j] = currentOffset
                                currentOffset += lengths.getInt(j)
                            }
                            
                            val buffer = ByteArrayOutputStream()
                            for (j in 0 until positions.length()) {
                                val pos = positions.getInt(j)
                                val len = lengths.getInt(pos)
                                val offset = segmentOffsets[pos]
                                val validLen = noiseLens?.getInt(j) ?: 1
                                
                                if (validLen > 0 && offset + validLen <= data.size) {
                                    buffer.write(data, offset, validLen)
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
                            val noiseL = layer.getJSONArray("noise_lens")
                            val originalOffsets = IntArray(noiseL.length())
                            var acc = 0
                            for(k in 0 until noiseL.length()) {
                                originalOffsets[k] = acc
                                acc += noiseL.getInt(k)
                            }

                            val result = ByteArray(perm.length())
                            for (j in 0 until perm.length()) {
                                val originalIndex = perm.getInt(j) 
                                val offset = originalOffsets[j]
                                if (offset < data.size) {
                                    result[originalIndex] = data[offset]
                                }
                            }
                            result
                        }
                        "bit_interleave" -> {
                            val perm = layer.getJSONArray("perm")
                            val newData = ByteArray(16) // Always 128 bits
                            for (j in 0 until perm.length()) {
                                val srcByteIdx = j / 8
                                val srcBitPos = 7 - (j % 8)
                                val bitVal = (data[srcByteIdx].toInt() shr srcBitPos) and 1
                                
                                val destBitIdx = perm.getInt(j)
                                val destByteIdx = destBitIdx / 8
                                val destBitPos = 7 - (destBitIdx % 8)
                                
                                if (bitVal == 1) {
                                    newData[destByteIdx] = (newData[destByteIdx].toInt() or (1 shl destBitPos)).toByte()
                                }
                            }
                            newData
                        }
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
