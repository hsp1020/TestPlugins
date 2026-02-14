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
import kotlinx.coroutines.delay
import kotlin.experimental.xor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [Version: v14-Universe-BruteForce]
 * 1. Key Gen Explosion: 6 Boolean Flags -> 64 Variants of Key Generation Logic.
 * 2. Decrypt Explosion: 4 IV Modes x 512 Byte Offsets -> 2048 Decrypt Attempts per Key.
 * 3. Total: ~131,072 attempts per segment. "God Mode" enabled.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v14]"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("$TAG getUrl: $url")
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
            val requestHeaders = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA)
            val response = app.get(url = cleanUrl, headers = requestHeaders, interceptor = resolver)
            
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
                delay(200) // Wait for cookies
                
                var cookie = CookieManager.getInstance().getCookie(capturedUrl)
                if (cookie.isNullOrEmpty()) {
                    cookie = CookieManager.getInstance().getCookie("https://player.bunny-frame.online")
                }
                val finalCookie = cookie ?: ""

                capturedHeaders = mutableMapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to "https://player.bunny-frame.online/",
                    "Origin" to "https://player.bunny-frame.online",
                    "Cookie" to finalCookie,
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
            } catch (e: Exception) { e.printStackTrace() }
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
        
        // Multi-Key (64 variants)
        data class KeyCandidate(val keyBytes: ByteArray, val description: String)
        @Volatile private var candidateKeys: List<KeyCandidate> = emptyList()
        @Volatile private var confirmedKey: KeyCandidate? = null
        
        // Decrypt Profile (Cached Success)
        data class DecryptProfile(val ivMode: Int, val trimOffset: Int)
        @Volatile private var confirmedProfile: DecryptProfile? = null

        private val VER = "[Bunny-v14-Proxy]"

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
            confirmedKey = null
            confirmedProfile = null
            candidateKeys = emptyList()
        }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }
        fun setTargetKeyUrl(url: String) { targetKeyUrl = url }

        private fun ensureKey() {
            if (confirmedKey != null || candidateKeys.isNotEmpty()) return
            if (targetKeyUrl == null) return

            runBlocking {
                try {
                    val cleanKeyUrl = targetKeyUrl!!.replace(Regex("[?&]mode=obfuscated"), "")
                    val res = app.get(cleanKeyUrl, headers = currentHeaders)
                    var rawData = res.body.bytes()

                    if (rawData.size > 100 && rawData[0] == '{'.code.toByte()) {
                        val jsonStr = String(rawData).trim()
                        // 64 Combinations Generation
                        candidateKeys = BunnyJsonDecryptor.generateMassiveKeys(jsonStr)
                        println("$VER Key Gen Complete. Candidates: ${candidateKeys.size}")
                    } else if (rawData.size == 16) {
                        confirmedKey = KeyCandidate(rawData, "Raw-16")
                    }
                } catch (e: Exception) { e.printStackTrace() }
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

                                // [Universe Brute Force]
                                val decrypted = runUniverseBruteForce(rawData, seq)
                                
                                if (decrypted != null) {
                                    output.write(decrypted)
                                } else {
                                    println("$VER FINAL FAIL after 130k attempts. Sending Raw.")
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

        private fun runUniverseBruteForce(data: ByteArray, seq: Long): ByteArray? {
            // 1. Fast Path (캐시된 정답이 있으면 1번만 수행)
            if (confirmedKey != null && confirmedProfile != null) {
                val p = confirmedProfile!!
                val dec = attemptDecrypt(data, confirmedKey!!.keyBytes, seq, p.ivMode, p.trimOffset)
                if (isValidTS(dec)) return dec
                // 캐시 실패시 초기화 후 전수 조사 재진입
                confirmedKey = null; confirmedProfile = null
            }

            // 2. Universe Scan
            val keys = if (confirmedKey != null) listOf(confirmedKey!!) else candidateKeys
            val ivModes = listOf(1, 3, 2, 0) // Std, Zero, LE, Expl
            
            // Loop Order: Key -> Offset -> IV (Cache locality optimization)
            for (k in keys) {
                // Offset 0..512 (Blind Scan)
                for (offset in 0..512) {
                    // Quick length check
                    if (data.size <= offset) break
                    
                    for (ivMode in ivModes) {
                        val dec = attemptDecrypt(data, k.keyBytes, seq, ivMode, offset)
                        if (isValidTS(dec)) {
                            // [JACKPOT]
                            println("$VER CRACK SUCCESS! Key:[${k.description}], IV:$ivMode, Offset:$offset")
                            confirmedKey = k
                            confirmedProfile = DecryptProfile(ivMode, offset)
                            return dec
                        }
                    }
                }
            }
            return null
        }

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, seq: Long, ivMode: Int, offset: Int): ByteArray? {
            try {
                val iv = ByteArray(16)
                when (ivMode) {
                    0 -> {
                        if (!playlistIv.isNullOrEmpty()) {
                             val hex = playlistIv!!.removePrefix("0x")
                             hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                        }
                    }
                    1 -> ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN).putLong(8, seq)
                    2 -> ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN).putLong(8, seq)
                    3 -> {} // Zero
                }

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                
                // Copy range creates new array, can be slow but safe
                val slice = data.copyOfRange(offset, data.size)
                return cipher.doFinal(slice)
            } catch (e: Exception) { return null }
        }

        private fun isValidTS(data: ByteArray?): Boolean {
            if (data == null || data.size < 376) return false
            // Check 0x47 sync byte in first 1KB
            val limit = minOf(data.size, 1024)
            for (i in 0 until limit - 188) {
                if (data[i] == 0x47.toByte() && data[i+188] == 0x47.toByte()) return true
            }
            return false
        }
    }

    object BunnyJsonDecryptor {
        private fun decodeBase64(input: String): ByteArray = Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        
        // 64 Combinations
        fun generateMassiveKeys(jsonStr: String): List<ProxyWebServer.KeyCandidate> {
            val list = mutableListOf<ProxyWebServer.KeyCandidate>()
            val bools = listOf(true, false)
            
            // 6 Nested Loops = 2^6 = 64 variants
            for (rotLeft in bools) {
                for (intGather in bools) {
                    for (intBitOrderMsb in bools) {
                        for (segAcc in bools) {
                            for (segIdxPerm in bools) {
                                for (xorBack in bools) {
                                    val key = decryptInternal(jsonStr, rotLeft, intGather, intBitOrderMsb, segAcc, segIdxPerm, xorBack)
                                    if (key != null) {
                                        val desc = "RL:$rotLeft,IG:$intGather,BM:$intBitOrderMsb,SA:$segAcc,SP:$segIdxPerm,XB:$xorBack"
                                        list.add(ProxyWebServer.KeyCandidate(key, desc))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return list
        }

        private fun decryptInternal(
            jsonStr: String, 
            rotLeft: Boolean, 
            intGather: Boolean, 
            intBitOrderMsb: Boolean,
            segAcc: Boolean, 
            segIdxPerm: Boolean,
            xorBack: Boolean
        ): ByteArray? {
            try {
                val json = JSONObject(jsonStr)
                var data = decodeBase64(json.getString("encrypted_key"))
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
                            val maskBytes = decodeBase64(layer.getString("xor_mask"))
                            xor(data, maskBytes)
                        }
                        "decoy_shuffle" -> {
                            // Standard Decoy Logic (Fixed)
                            val positions = layer.getJSONArray("real_positions")
                            val lengths = layer.getJSONArray("segment_lengths")
                            val segmentOffsets = IntArray(lengths.length())
                            var currentOffset = 0
                            for (j in 0 until lengths.length()) { segmentOffsets[j] = currentOffset; currentOffset += lengths.getInt(j) }
                            val buffer = ByteArrayOutputStream()
                            for (j in 0 until positions.length()) {
                                val pos = positions.getInt(j); val len = lengths.getInt(pos); val offset = segmentOffsets[pos]; val validLen = noiseLens?.getInt(j) ?: 1
                                if (validLen > 0 && offset + validLen <= data.size) buffer.write(data, offset, validLen)
                            }
                            buffer.toByteArray()
                        }
                        "xor_chain" -> {
                            val initKeyB64 = layer.optString("init_key", null)
                            val ivBytes = if (initKeyB64 != null) decodeBase64(initKeyB64) else ByteArray(0)
                            val newData = data.clone()
                            if (xorBack) {
                                for (j in newData.size - 1 downTo 1) newData[j] = newData[j] xor newData[j-1]
                            } else {
                                for (j in 1 until newData.size) newData[j] = newData[j] xor newData[j-1]
                            }
                            if (newData.isNotEmpty() && ivBytes.isNotEmpty()) newData[0] = newData[0] xor ivBytes[0]
                            newData
                        }
                        "sbox" -> {
                            val invSbox = decodeBase64(layer.getString("inverse_sbox"))
                            val newData = ByteArray(data.size)
                            for (j in data.indices) newData[j] = invSbox[data[j].toInt() and 0xFF]
                            newData
                        }
                        "bit_rotate" -> {
                            val rotations = layer.getJSONArray("rotations")
                            val newData = ByteArray(data.size)
                            for (j in data.indices) {
                                val rot = rotations.getInt(j % rotations.length())
                                val b = data[j].toInt() and 0xFF
                                val r = if (rotLeft) (b shl rot) or (b ushr (8 - rot))
                                        else (b ushr rot) or (b shl (8 - rot))
                                newData[j] = r.toByte()
                            }
                            newData
                        }
                        "segment_noise" -> {
                            val perm = layer.getJSONArray("perm")
                            val noiseL = layer.getJSONArray("noise_lens")
                            var currentReadOffset = 0
                            val result = ByteArray(perm.length())
                            for (j in 0 until perm.length()) {
                                val originalIndex = perm.getInt(j)
                                val lenIdx = if (segIdxPerm) originalIndex else j
                                val chunkLen = noiseL.getInt(lenIdx)
                                
                                val srcIdx = if (segAcc) currentReadOffset else 0 // Simplified variant check
                                if (currentReadOffset < data.size) result[originalIndex] = data[currentReadOffset]
                                currentReadOffset += chunkLen
                            }
                            result
                        }
                        "bit_interleave" -> {
                            val perm = layer.getJSONArray("perm")
                            val newData = ByteArray(16)
                            for (j in 0 until perm.length()) {
                                val bitVal: Int
                                val destByteIdx: Int
                                val destBitPos: Int
                                
                                if (intGather) {
                                    val srcBitIdx = perm.getInt(j)
                                    val srcBitPos = if (intBitOrderMsb) (7 - (srcBitIdx % 8)) else (srcBitIdx % 8)
                                    bitVal = (data[srcBitIdx / 8].toInt() shr srcBitPos) and 1
                                    destByteIdx = j / 8
                                    destBitPos = if (intBitOrderMsb) (7 - (j % 8)) else (j % 8)
                                } else {
                                    val srcBitIdx = j
                                    val srcBitPos = if (intBitOrderMsb) (7 - (srcBitIdx % 8)) else (srcBitIdx % 8)
                                    bitVal = (data[srcBitIdx / 8].toInt() shr srcBitPos) and 1
                                    val destTotalBit = perm.getInt(j)
                                    destByteIdx = destTotalBit / 8
                                    destBitPos = if (intBitOrderMsb) (7 - (destTotalBit % 8)) else (destTotalBit % 8)
                                }

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
            } catch (e: Exception) { return null }
        }

        private fun xor(data: ByteArray, key: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            for (i in data.indices) out[i] = data[i] xor key[i % key.size]
            return out
        }
    }
}
