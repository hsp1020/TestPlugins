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
 * [Version: v16-Singularity]
 * 1. The "God" Loop: 13 Boolean Parameters = 8192 Key Variants.
 * 2. Decrypt Scan: 4 IV Modes x 512 Offsets = 2048 Attempts per Key.
 * 3. Total Scope: 16,777,216 combinations checked.
 * 4. Optimization: Decrypt only 1 block (16 bytes) per check to ensure speed.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v16]"
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
                delay(200) 
                
                var cookie = CookieManager.getInstance().getCookie(capturedUrl)
                if (cookie.isNullOrEmpty()) cookie = CookieManager.getInstance().getCookie("https://player.bunny-frame.online")
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
        
        // Final Key
        @Volatile private var confirmedKey: ByteArray? = null
        // Raw JSON String for On-Demand Generation
        @Volatile private var rawKeyJson: String? = null
        
        // Decrypt Profile
        data class DecryptProfile(val ivMode: Int, val trimOffset: Int)
        @Volatile private var confirmedProfile: DecryptProfile? = null

        private val VER = "[Bunny-v16-Proxy]"

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
            rawKeyJson = null
        }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }
        fun setTargetKeyUrl(url: String) { targetKeyUrl = url }

        private fun ensureKey() {
            if (confirmedKey != null || rawKeyJson != null) return
            if (targetKeyUrl == null) return

            runBlocking {
                try {
                    val cleanKeyUrl = targetKeyUrl!!.replace(Regex("[?&]mode=obfuscated"), "")
                    val res = app.get(cleanKeyUrl, headers = currentHeaders)
                    var rawData = res.body.bytes()

                    if (rawData.size > 100 && rawData[0] == '{'.code.toByte()) {
                        rawKeyJson = String(rawData).trim()
                        println("$VER JSON Key Downloaded. Ready for Brute Force.")
                    } else if (rawData.size == 16) {
                        confirmedKey = rawData
                        println("$VER Raw 16-byte Key Confirmed.")
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

                                // [Singularity Scan]
                                val decrypted = performSingularityScan(rawData, seq)
                                
                                if (decrypted != null) {
                                    output.write(decrypted)
                                } else {
                                    println("$VER SCAN FAILED (16M attempts). Sending Raw.")
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

        private fun performSingularityScan(data: ByteArray, seq: Long): ByteArray? {
            // 1. Fast Path (캐시된 정답)
            if (confirmedKey != null && confirmedProfile != null) {
                val p = confirmedProfile!!
                val dec = attemptDecrypt(data, confirmedKey!!, seq, p.ivMode, p.trimOffset)
                if (isValidTS(dec)) return dec
                confirmedKey = null; confirmedProfile = null
            }

            // 2. Prepare Key Generation Loop (8192 variants)
            val json = rawKeyJson
            val keyList = if (json != null) {
                // Generate logic DNA on the fly to save memory/time if needed, 
                // but pre-generating 8192 16-byte keys is fast (128KB total).
                BunnyJsonDecryptor.generateSingularityKeys(json)
            } else if (confirmedKey != null) {
                listOf(BunnyJsonDecryptor.KeyDNA(confirmedKey!!, "Raw"))
            } else {
                return null
            }

            val ivModes = listOf(1, 3, 2, 0) // Std, Zero, LE, Expl
            
            // 3. The "God Loop"
            // Loop Order: Key -> Offset -> IV
            for (dna in keyList) {
                // Scan Offset 0..512 (Blind)
                for (offset in 0..512) {
                    if (data.size <= offset) break
                    
                    for (ivMode in ivModes) {
                        // Optimization: Decrypt only FIRST BLOCK (16 bytes) or TS Packet size (188)
                        // If it matches 0x47, then do full decrypt.
                        val decChunk = attemptDecryptChunk(data, dna.key, seq, ivMode, offset, 188)
                        
                        if (isValidTS(decChunk)) {
                            // [FOUND]
                            println("$VER CRACK SUCCESS! DNA:[${dna.desc}], IV:$ivMode, Off:$offset")
                            confirmedKey = dna.key
                            confirmedProfile = DecryptProfile(ivMode, offset)
                            return attemptDecrypt(data, dna.key, seq, ivMode, offset)
                        }
                    }
                }
            }
            return null
        }

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, seq: Long, ivMode: Int, offset: Int): ByteArray? {
            try {
                val iv = generateIV(ivMode, seq)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                return cipher.doFinal(data.copyOfRange(offset, data.size))
            } catch (e: Exception) { return null }
        }
        
        private fun attemptDecryptChunk(data: ByteArray, key: ByteArray, seq: Long, ivMode: Int, offset: Int, length: Int): ByteArray? {
            try {
                val iv = generateIV(ivMode, seq)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                
                val len = minOf(data.size - offset, length)
                val alignLen = (len / 16) * 16
                if (alignLen <= 0) return null
                
                val slice = data.copyOfRange(offset, offset + alignLen)
                return cipher.doFinal(slice)
            } catch (e: Exception) { return null }
        }
        
        private fun generateIV(ivMode: Int, seq: Long): ByteArray {
            val iv = ByteArray(16)
            when (ivMode) {
                0 -> if (!playlistIv.isNullOrEmpty()) {
                        val hex = playlistIv!!.removePrefix("0x")
                        hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                     }
                1 -> ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN).putLong(8, seq)
                2 -> ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN).putLong(8, seq)
            }
            return iv
        }

        private fun isValidTS(data: ByteArray?): Boolean {
            if (data == null || data.size < 188) return false
            // Check 0x47 in first block (most efficient)
            return data[0] == 0x47.toByte() || (data.size >= 188 && data[188] == 0x47.toByte())
        }
    }

    object BunnyJsonDecryptor {
        data class KeyDNA(val key: ByteArray, val desc: String)

        private fun decodeBase64(input: String, flags: Int): ByteArray = Base64.decode(input, flags)
        
        // 8192 Combinations
        fun generateSingularityKeys(jsonStr: String): List<KeyDNA> {
            val list = mutableListOf<KeyDNA>()
            
            // 13 Parameters (0..8191 Loop)
            for (i in 0 until 8192) {
                // Extract bits
                val p_lyr_fwd   = (i shr 0) and 1 == 1
                val p_b64_url   = (i shr 1) and 1 == 1
                val p_rot_l     = (i shr 2) and 1 == 1
                val p_rot_irev  = (i shr 3) and 1 == 1
                val p_int_gat   = (i shr 4) and 1 == 1
                val p_int_msb   = (i shr 5) and 1 == 1
                val p_seg_gat   = (i shr 6) and 1 == 1
                val p_seg_perm  = (i shr 7) and 1 == 1
                val p_seg_acc   = (i shr 8) and 1 == 1
                val p_xor_fwd   = (i shr 9) and 1 == 1
                val p_xor_b0    = (i shr 10) and 1 == 1
                val p_xor_iv_e  = (i shr 11) and 1 == 1
                val p_sbox_inv  = (i shr 12) and 1 == 1

                val key = decryptInternal(jsonStr, 
                    p_lyr_fwd, p_b64_url, p_rot_l, p_rot_irev,
                    p_int_gat, p_int_msb,
                    p_seg_gat, p_seg_perm, p_seg_acc,
                    p_xor_fwd, p_xor_b0, p_xor_iv_e,
                    p_sbox_inv
                )
                if (key != null) {
                    list.add(KeyDNA(key, "DNA:$i"))
                }
            }
            return list
        }

        private fun decryptInternal(
            jsonStr: String,
            lyrFwd: Boolean, b64Url: Boolean, 
            rotL: Boolean, rotIRev: Boolean,
            intGat: Boolean, intMsb: Boolean,
            segGat: Boolean, segPerm: Boolean, segAcc: Boolean,
            xorFwd: Boolean, xorB0: Boolean, xorIvE: Boolean,
            sboxInv: Boolean
        ): ByteArray? {
            try {
                val json = JSONObject(jsonStr)
                val b64Flag = if (b64Url) Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP else Base64.DEFAULT
                var data = decodeBase64(json.getString("encrypted_key"), b64Flag)
                
                val layersJson = json.getJSONArray("layers")
                val layers = mutableListOf<JSONObject>()
                for(i in 0 until layersJson.length()) layers.add(layersJson.getJSONObject(i))
                
                val orderedLayers = if (lyrFwd) layers else layers.asReversed()
                
                var noiseLens: JSONArray? = null
                for (l in layers) if (l.getString("name") == "segment_noise") noiseLens = l.getJSONArray("noise_lens")

                for (layer in orderedLayers) {
                    val name = layer.getString("name")
                    data = when(name) {
                        "final_encrypt" -> {
                            val mask = decodeBase64(layer.getString("xor_mask"), b64Flag)
                            for(i in data.indices) data[i] = (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
                            data
                        }
                        "decoy_shuffle" -> {
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
                            val ivBytes = if(layer.has("init_key")) decodeBase64(layer.getString("init_key"), b64Flag) else ByteArray(0)
                            val newData = data.clone()
                            val start = if(xorB0) 0 else 1
                            if (xorFwd) {
                                for (j in start until newData.size - 1) newData[j+1] = (newData[j+1].toInt() xor newData[j].toInt()).toByte()
                            } else {
                                for (j in newData.size - 1 downTo start + 1) newData[j] = (newData[j].toInt() xor newData[j-1].toInt()).toByte()
                            }
                            if (ivBytes.isNotEmpty()) {
                                val idx = if (xorIvE) newData.lastIndex else 0
                                newData[idx] = (newData[idx].toInt() xor ivBytes[0].toInt()).toByte()
                            }
                            newData
                        }
                        "sbox" -> {
                            val sboxStr = if(sboxInv) "inverse_sbox" else "inverse_sbox" // JSON key name assumption
                            val invSbox = decodeBase64(layer.getString(sboxStr), b64Flag) 
                            for (j in data.indices) data[j] = invSbox[data[j].toInt() and 0xFF]
                            data
                        }
                        "bit_rotate" -> {
                            val rotations = layer.getJSONArray("rotations")
                            for (j in data.indices) {
                                val rIdx = if(rotIRev) (rotations.length() - 1 - (j % rotations.length())) else (j % rotations.length())
                                val rot = rotations.getInt(rIdx)
                                val b = data[j].toInt() and 0xFF
                                val r = if (rotL) (b shl rot) or (b ushr (8 - rot)) else (b ushr rot) or (b shl (8 - rot))
                                data[j] = r.toByte()
                            }
                            data
                        }
                        "segment_noise" -> {
                            val perm = layer.getJSONArray("perm")
                            val noiseL = layer.getJSONArray("noise_lens")
                            var currentReadOffset = 0
                            val result = ByteArray(perm.length())
                            for (j in 0 until perm.length()) {
                                val originalIndex = perm.getInt(j)
                                val lenIdx = if (segPerm) originalIndex else j
                                val chunkLen = noiseL.getInt(lenIdx)
                                
                                // Gather: Res[i] = Src[Off] vs Scatter: Res[P[i]] = Src[Off]
                                val destIdx = if (segGat) j else originalIndex
                                val srcOff = if (segAcc) currentReadOffset else (j * chunkLen) // Simplified linear
                                
                                if (srcOff < data.size) result[destIdx] = data[srcOff]
                                if (segAcc) currentReadOffset += chunkLen
                            }
                            result
                        }
                        "bit_interleave" -> {
                            val perm = layer.getJSONArray("perm")
                            val newData = ByteArray(16)
                            for (j in 0 until perm.length()) {
                                val srcIdx = if (intGat) perm.getInt(j) else j
                                val dstIdx = if (intGat) j else perm.getInt(j)
                                
                                val srcByte = srcIdx / 8; val srcBit = if (intMsb) 7 - (srcIdx % 8) else srcIdx % 8
                                val dstByte = dstIdx / 8; val dstBit = if (intMsb) 7 - (dstIdx % 8) else dstIdx % 8
                                
                                val bit = (data[srcByte].toInt() shr srcBit) and 1
                                if (bit == 1) newData[dstByte] = (newData[dstByte].toInt() or (1 shl dstBit)).toByte()
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
            for (i in data.indices) data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
            return data
        }
    }
}
