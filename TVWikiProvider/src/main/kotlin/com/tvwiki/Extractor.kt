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
import java.util.Arrays

/**
 * [Version: v21-BuildFix-Final]
 * 1. Build Error Fixed: 'confirmedKey' 접근 시 로컬 변수 캡처(Local Capture) 방식을 사용하여 Null Safety 에러 해결.
 * 2. Logic: v20의 '키 패턴 매칭(01 0E 00)' 및 'Blind Trim' 로직 100% 유지.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v21]"
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
                delay(500)
                
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
        
        @Volatile private var confirmedKey: ByteArray? = null
        @Volatile private var rawKeyJson: String? = null
        
        data class DecryptProfile(val ivMode: Int, val trimOffset: Int)
        @Volatile private var confirmedProfile: DecryptProfile? = null

        private val VER = "[Bunny-v21-Proxy]"

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
            if (confirmedKey != null) return
            if (targetKeyUrl == null) return

            runBlocking {
                try {
                    val cleanKeyUrl = targetKeyUrl!!.replace(Regex("[?&]mode=obfuscated"), "")
                    val res = app.get(cleanKeyUrl, headers = currentHeaders)
                    var rawData = res.body.bytes()

                    if (rawData.size > 100 && rawData[0] == '{'.code.toByte()) {
                        val jsonStr = String(rawData).trim()
                        val matchedKey = BunnyPatternMatcher.findCorrectKey(jsonStr)
                        if (matchedKey != null) {
                            confirmedKey = matchedKey
                            println("$VER KEY FOUND BY PATTERN! ${bytesToHex(matchedKey)}")
                        } else {
                            println("$VER FATAL: No key matched pattern 01 0E 00.")
                        }
                    } else if (rawData.size == 16) {
                        confirmedKey = rawData
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        
        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 15000 
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
                                
                                // [Fix] confirmedKey를 로컬 변수에 캡처하여 Smart Cast 보장
                                val currentKey = confirmedKey
                                val decrypted = if (currentKey != null) {
                                    blindTrimAndDecrypt(rawData, currentKey, seq)
                                } else {
                                    null
                                }
                                output.write(decrypted ?: rawData)
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

        private fun blindTrimAndDecrypt(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            // 캐시된 프로필 확인
            val profile = confirmedProfile
            if (profile != null) {
                return attemptDecrypt(data, key, seq, profile.ivMode, profile.trimOffset)
            }

            val ivModes = listOf(1, 3, 2, 0)
            for (offset in 0..256) {
                if (data.size <= offset + 188) break
                for (ivMode in ivModes) {
                    val dec = attemptDecrypt(data, key, seq, ivMode, offset)
                    if (dec != null && isValidTS(dec)) {
                        confirmedProfile = DecryptProfile(ivMode, offset)
                        println("$VER Offset Found: $offset, IV: $ivMode")
                        return dec
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
            return data.isNotEmpty() && data[0] == 0x47.toByte()
        }
        
        private fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (b in bytes) sb.append(String.format("%02X", b))
            return sb.toString()
        }
    }

    class BunnyPatternMatcher(jsonStr: String) {
        companion object {
            fun findCorrectKey(jsonStr: String): ByteArray? {
                val matcher = BunnyPatternMatcher(jsonStr)
                return matcher.scanForPattern()
            }
        }

        private val baseKeyDefault: ByteArray
        private val baseKeyUrl: ByteArray
        private val workBuffer: ByteArray
        private val tempBuffer: ByteArray
        private val layers: List<LayerInstruction>

        private sealed class LayerInstruction {
            class FinalEncrypt(val maskDefault: ByteArray, val maskUrl: ByteArray) : LayerInstruction()
            class DecoyShuffle(val positions: IntArray, val lens: IntArray, val offsets: IntArray) : LayerInstruction()
            class XorChain(val ivDefault: ByteArray, val ivUrl: ByteArray) : LayerInstruction()
            class Sbox(val invDefault: ByteArray, val invUrl: ByteArray) : LayerInstruction()
            class BitRotate(val rots: IntArray) : LayerInstruction()
            class SegNoise(val perm: IntArray, val noiseLens: IntArray) : LayerInstruction()
            class BitInterleave(val perm: IntArray) : LayerInstruction()
        }

        init {
            val json = JSONObject(jsonStr)
            val keyStr = json.getString("encrypted_key")
            baseKeyDefault = Base64.decode(keyStr, Base64.DEFAULT)
            baseKeyUrl = Base64.decode(keyStr, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            workBuffer = ByteArray(maxOf(baseKeyDefault.size, baseKeyUrl.size) + 1024)
            tempBuffer = ByteArray(workBuffer.size)

            val layersJson = json.getJSONArray("layers")
            val tempLayers = ArrayList<LayerInstruction>()

            for (i in 0 until layersJson.length()) {
                val l = layersJson.getJSONObject(i)
                when (l.getString("name")) {
                    "final_encrypt" -> {
                        val mStr = l.getString("xor_mask")
                        tempLayers.add(LayerInstruction.FinalEncrypt(
                            Base64.decode(mStr, Base64.DEFAULT),
                            Base64.decode(mStr, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        ))
                    }
                    "bit_rotate" -> {
                        val r = l.getJSONArray("rotations")
                        val arr = IntArray(r.length()) { k -> r.getInt(k) }
                        tempLayers.add(LayerInstruction.BitRotate(arr))
                    }
                    "segment_noise" -> {
                        val p = l.getJSONArray("perm"); val n = l.getJSONArray("noise_lens")
                        tempLayers.add(LayerInstruction.SegNoise(
                            IntArray(p.length()) { k -> p.getInt(k) },
                            IntArray(n.length()) { k -> n.getInt(k) }
                        ))
                    }
                    "bit_interleave" -> {
                        val p = l.getJSONArray("perm")
                        tempLayers.add(LayerInstruction.BitInterleave(IntArray(p.length()) { k -> p.getInt(k) }))
                    }
                    "sbox" -> {
                        val key = if (l.has("inverse_sbox")) "inverse_sbox" else "sbox"
                        val str = l.getString(key)
                        tempLayers.add(LayerInstruction.Sbox(
                            Base64.decode(str, Base64.DEFAULT),
                            Base64.decode(str, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        ))
                    }
                    "xor_chain" -> {
                        val str = l.optString("init_key", null)
                        val ivD = if (str != null) Base64.decode(str, Base64.DEFAULT) else ByteArray(0)
                        val ivU = if (str != null) Base64.decode(str, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) else ByteArray(0)
                        tempLayers.add(LayerInstruction.XorChain(ivD, ivU))
                    }
                    "decoy_shuffle" -> {
                         val pos = l.getJSONArray("real_positions")
                         val len = l.getJSONArray("segment_lengths")
                         val posArr = IntArray(pos.length()) { k -> pos.getInt(k) }
                         val lenArr = IntArray(len.length()) { k -> len.getInt(k) }
                         val offArr = IntArray(lenArr.size)
                         var curr = 0
                         for (k in lenArr.indices) { offArr[k] = curr; curr += lenArr[k] }
                         tempLayers.add(LayerInstruction.DecoyShuffle(posArr, lenArr, offArr))
                    }
                }
            }
            layers = tempLayers
        }

        fun scanForPattern(): ByteArray? {
            for (i in 0 until 8192) {
                generateKey(i)
                if (checkPattern()) {
                    return workBuffer.copyOf(16)
                }
            }
            return null
        }
        
        private fun checkPattern(): Boolean {
            if (workBuffer[0] != 0x01.toByte() || workBuffer[1] != 0x0E.toByte() || workBuffer[2] != 0x00.toByte()) return false
            var mask = 0
            for (k in 3..9) {
                val b = workBuffer[k].toInt()
                if (b < 1 || b > 7) return false
                if ((mask and (1 shl b)) != 0) return false
                mask = mask or (1 shl b)
            }
            return mask == 0xFE
        }

        private fun generateKey(variant: Int) {
            val p_lyr_fwd   = (variant shr 0) and 1 == 1
            val p_b64_url   = (variant shr 1) and 1 == 1
            val p_rot_l     = (variant shr 2) and 1 == 1
            val p_rot_irev  = (variant shr 3) and 1 == 1
            val p_int_gat   = (variant shr 4) and 1 == 1
            val p_int_msb   = (variant shr 5) and 1 == 1
            val p_seg_gat   = (variant shr 6) and 1 == 1
            val p_seg_perm  = (variant shr 7) and 1 == 1
            val p_seg_acc   = (variant shr 8) and 1 == 1
            val p_xor_fwd   = (variant shr 9) and 1 == 1
            val p_xor_b0    = (variant shr 10) and 1 == 1
            val p_xor_iv_e  = (variant shr 11) and 1 == 1
            val p_sbox_inv  = (variant shr 12) and 1 == 1

            val srcKey = if (p_b64_url) baseKeyUrl else baseKeyDefault
            val size = srcKey.size
            System.arraycopy(srcKey, 0, workBuffer, 0, size)
            var currentSize = size

            val iter = if (p_lyr_fwd) layers.iterator() else layers.asReversed().iterator()

            while (iter.hasNext()) {
                val layer = iter.next()
                when (layer) {
                    is LayerInstruction.FinalEncrypt -> {
                        val mask = if (p_b64_url) layer.maskUrl else layer.maskDefault
                        if (mask.isNotEmpty()) {
                            for (i in 0 until currentSize) {
                                workBuffer[i] = (workBuffer[i].toInt() xor mask[i % mask.size].toInt()).toByte()
                            }
                        }
                    }
                    is LayerInstruction.BitRotate -> {
                        val rots = layer.rots; val len = rots.size
                        for (i in 0 until currentSize) {
                            val rIdx = if (p_rot_irev) (len - 1 - (i % len)) else (i % len)
                            val rot = rots[rIdx]
                            val b = workBuffer[i].toInt() and 0xFF
                            val r = if (p_rot_l) (b shl rot) or (b ushr (8 - rot)) else (b ushr rot) or (b shl (8 - rot))
                            workBuffer[i] = r.toByte()
                        }
                    }
                    is LayerInstruction.SegNoise -> {
                        val perm = layer.perm; val noiseLens = layer.noiseLens
                        System.arraycopy(workBuffer, 0, tempBuffer, 0, currentSize)
                        var currentReadOffset = 0
                        for (i in 0 until perm.size) {
                             if (i >= currentSize) break 
                             val originalIndex = perm[i]
                             val lenIdx = if (p_seg_perm) originalIndex else i
                             if (lenIdx < noiseLens.size) {
                                 val chunkLen = noiseLens[lenIdx]
                                 val destIdx = if (p_seg_gat) i else originalIndex
                                 val srcOff = if (p_seg_acc) currentReadOffset else (i * chunkLen) 
                                 if (destIdx < currentSize && srcOff < currentSize) workBuffer[destIdx] = tempBuffer[srcOff]
                                 if (p_seg_acc) currentReadOffset += chunkLen
                             }
                        }
                    }
                    is LayerInstruction.BitInterleave -> {
                        val perm = layer.perm
                        System.arraycopy(workBuffer, 0, tempBuffer, 0, currentSize)
                        val loopLen = minOf(perm.size, currentSize * 8)
                        for(x in 0 until 16) if (x < currentSize) workBuffer[x] = 0
                        for (j in 0 until loopLen) {
                            val srcIdx = if (p_int_gat) perm[j] else j
                            val dstIdx = if (p_int_gat) j else perm[j]
                            val srcByte = srcIdx / 8; val srcBit = if (p_int_msb) 7 - (srcIdx % 8) else srcIdx % 8
                            val dstByte = dstIdx / 8; val dstBit = if (p_int_msb) 7 - (dstIdx % 8) else dstIdx % 8
                            if (srcByte < currentSize && dstByte < currentSize) {
                                val bit = (tempBuffer[srcByte].toInt() shr srcBit) and 1
                                if (bit == 1) workBuffer[dstByte] = (workBuffer[dstByte].toInt() or (1 shl dstBit)).toByte()
                            }
                        }
                    }
                    is LayerInstruction.Sbox -> {
                        val sbox = if (p_b64_url) layer.invUrl else layer.invDefault 
                        if (sbox.size == 256) {
                            for (i in 0 until currentSize) workBuffer[i] = sbox[workBuffer[i].toInt() and 0xFF]
                        }
                    }
                    is LayerInstruction.XorChain -> {
                        val iv = if (p_b64_url) layer.ivUrl else layer.ivDefault
                        val start = if (p_xor_b0) 0 else 1
                        System.arraycopy(workBuffer, 0, tempBuffer, 0, currentSize)
                        if (p_xor_fwd) {
                            for (j in start until currentSize - 1) workBuffer[j+1] = (workBuffer[j+1].toInt() xor workBuffer[j].toInt()).toByte()
                        } else {
                            for (j in currentSize - 1 downTo start + 1) workBuffer[j] = (workBuffer[j].toInt() xor workBuffer[j-1].toInt()).toByte()
                        }
                        if (iv.isNotEmpty()) {
                            val idx = if (p_xor_iv_e) currentSize - 1 else 0
                            workBuffer[idx] = (workBuffer[idx].toInt() xor iv[0].toInt()).toByte()
                        }
                    }
                    is LayerInstruction.DecoyShuffle -> {
                        System.arraycopy(workBuffer, 0, tempBuffer, 0, currentSize)
                        val posArr = layer.positions; val lenArr = layer.lens; val offArr = layer.offsets
                        var writePtr = 0
                        for (i in 0 until posArr.size) {
                             val pos = posArr[i]
                             if (pos < lenArr.size && pos < offArr.size) {
                                 val len = lenArr[pos]
                                 val off = offArr[pos]
                                 if (off + len <= currentSize && writePtr + len <= workBuffer.size) {
                                     // Decoy Pick Logic: Always pick LAST byte (Common obfuscation)
                                     // To be safe, we rely on p_seg_acc bit to toggle First/Last pick
                                     val byteToKeep = if (p_seg_acc) tempBuffer[off + len - 1] else tempBuffer[off]
                                     workBuffer[writePtr] = byteToKeep
                                     writePtr += 1
                                 }
                             }
                        }
                         if (writePtr > 0) currentSize = writePtr
                    }
                }
            }
        }
    }
}
