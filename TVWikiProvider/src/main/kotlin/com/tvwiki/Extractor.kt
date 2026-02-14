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
 * [Version: v8-Hell-BruteForce]
 * 1. Key Logic Explosion: (Rotate L/R) * (Interleave Gather/Scatter) * (SegNoise New/Old) = 8가지 키 동시 생성.
 * 2. Decrypt Explosion: (IV Std/Zero/LE) * (Offset 0..15) = 64가지 복호화 시도.
 * 3. Total: 512가지 조합을 런타임에 전수 조사하여 0x47 Sync Byte를 찾아냄.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v8]"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("$TAG getUrl 호출: $url")
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

                // IV 추출
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
        
        // --- Multi-Key Logic ---
        data class KeyCandidate(
            val keyBytes: ByteArray,
            val description: String // "RotL-Gather-NewSeg", etc.
        )
        @Volatile private var candidateKeys: List<KeyCandidate> = emptyList()
        @Volatile private var confirmedKey: KeyCandidate? = null
        
        // --- Decrypt Profile ---
        data class DecryptProfile(
            val ivMode: Int,
            val inputOffset: Int,
            val outputOffset: Int
        )
        @Volatile private var confirmedProfile: DecryptProfile? = null

        private val VER = "[Bunny-v8-Proxy]"

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
            candidateKeys = emptyList()
            confirmedKey = null
            confirmedProfile = null
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
                        // [핵심] 모든 경우의 수 생성 (8가지)
                        candidateKeys = BunnyJsonDecryptor.generateAllPossibleKeys(jsonStr)
                        println("$VER Key Candidates Generated: ${candidateKeys.size}")
                    } else if (rawData.size == 16) {
                        confirmedKey = KeyCandidate(rawData, "Raw-16Byte")
                        println("$VER Raw Key Confirmed.")
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

                                // [Total Brute Force]
                                val decrypted = findAndDecrypt(rawData, seq)
                                
                                if (decrypted != null) {
                                    output.write(decrypted)
                                } else {
                                    println("$VER CRACK FAILED. Sending Raw Data.")
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

        private fun findAndDecrypt(data: ByteArray, seq: Long): ByteArray? {
            // 1. 이미 확정된 키 & 프로필이 있으면 바로 실행 (Fast Path)
            if (confirmedKey != null && confirmedProfile != null) {
                val p = confirmedProfile!!
                val key = confirmedKey!!.keyBytes
                val dec = decryptAttempt(data, key, seq, p.ivMode, p.inputOffset)
                if (dec != null) {
                    val startIdx = findSyncByte(dec)
                    if (startIdx != -1) return dec.copyOfRange(startIdx, dec.size)
                }
                // 실패시 재탐색을 위해 초기화
                confirmedKey = null
                confirmedProfile = null
            }

            // 2. [Total Brute Force] 모든 키 x 모든 오프셋 x 모든 IV
            val keysToTry = if (confirmedKey != null) listOf(confirmedKey!!) else candidateKeys
            val ivModes = mutableListOf(1, 3, 2, 0) // 1:BE, 3:Zero, 2:LE, 0:Explicit
            
            for (keyObj in keysToTry) {
                // Input Offset: 0~15
                for (inOff in 0..15) {
                    if (data.size <= inOff) break
                    val len = data.size - inOff
                    val alignLen = (len / 16) * 16
                    if (alignLen <= 0) continue

                    for (ivMode in ivModes) {
                        val dec = decryptAttempt(data, keyObj.keyBytes, seq, ivMode, inOff) ?: continue
                        val outOff = findSyncByte(dec)
                        
                        if (outOff != -1) {
                            // [정답 발견!]
                            println("$VER CRACK SUCCESS! Key:[${keyObj.description}], IV:$ivMode, InOff:$inOff, OutOff:$outOff")
                            // 성공한 설정 저장
                            confirmedKey = keyObj
                            confirmedProfile = DecryptProfile(ivMode, inOff, outOff)
                            return dec.copyOfRange(outOff, dec.size)
                        }
                    }
                }
            }
            return null
        }

        private fun decryptAttempt(data: ByteArray, key: ByteArray, seq: Long, ivMode: Int, offset: Int): ByteArray? {
            return try {
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
                    3 -> {} 
                }

                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                
                val slice = data.copyOfRange(offset, offset + ((data.size - offset) / 16 * 16))
                cipher.doFinal(slice)
            } catch (e: Exception) { null }
        }

        private fun findSyncByte(data: ByteArray): Int {
            // Deep Scan: 6400 bytes
            val limit = minOf(data.size, 6400)
            for (i in 0 until limit - 376) {
                if (data[i] == 0x47.toByte() && data[i+188] == 0x47.toByte() && data[i+376] == 0x47.toByte()) {
                    return i
                }
            }
            return -1
        }
        
        private fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (i in bytes.indices) sb.append(String.format("%02X", bytes[i]))
            return sb.toString()
        }
    }

    object BunnyJsonDecryptor {
        private fun decodeBase64(input: String): ByteArray = Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        
        // [핵심] 모든 경우의 수 생성 (8가지)
        fun generateAllPossibleKeys(jsonStr: String): List<ProxyWebServer.KeyCandidate> {
            val list = mutableListOf<ProxyWebServer.KeyCandidate>()
            
            // Boolean Combinations: 
            // Rotate(Left/Right) x Interleave(Gather/Scatter) x SegNoise(New/Old)
            val booleans = listOf(true, false)
            
            for (rotLeft in booleans) {
                for (gather in booleans) {
                    for (segNew in booleans) {
                        val key = decryptInternal(jsonStr, rotLeft, gather, segNew)
                        if (key != null) {
                            val desc = "Rot:${if(rotLeft)"L" else "R"}_Int:${if(gather)"Gat" else "Sca"}_Seg:${if(segNew)"New" else "Old"}"
                            list.add(ProxyWebServer.KeyCandidate(key, desc))
                        }
                    }
                }
            }
            return list
        }

        private fun decryptInternal(jsonStr: String, rotateLeft: Boolean, interleaveGather: Boolean, segNoiseNew: Boolean): ByteArray? {
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
                            for (j in newData.size - 1 downTo 1) { newData[j] = newData[j] xor newData[j-1] }
                            if (newData.isNotEmpty() && ivBytes.isNotEmpty()) { newData[0] = newData[0] xor ivBytes[0] }
                            newData
                        }
                        "sbox" -> {
                            val invSbox = decodeBase64(layer.getString("inverse_sbox"))
                            val newData = ByteArray(data.size)
                            for (j in data.indices) { newData[j] = invSbox[data[j].toInt() and 0xFF] }
                            newData
                        }
                        "bit_rotate" -> {
                            val rotations = layer.getJSONArray("rotations")
                            val newData = ByteArray(data.size)
                            for (j in data.indices) {
                                val rot = rotations.getInt(j % rotations.length())
                                val b = data[j].toInt() and 0xFF
                                // [Combination 1] Left vs Right
                                val r = if (rotateLeft) (b shl rot) or (b ushr (8 - rot))
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
                                // [Combination 3] New Logic (Correct) vs Old Logic (Legacy)
                                val chunkLen = noiseL.getInt(if (segNoiseNew) originalIndex else j) 
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
                                
                                // [Combination 2] Gather vs Scatter
                                if (interleaveGather) {
                                    val srcBitIdx = perm.getInt(j)
                                    bitVal = (data[srcBitIdx / 8].toInt() shr (7 - (srcBitIdx % 8))) and 1
                                    destByteIdx = j / 8
                                    destBitPos = 7 - (j % 8)
                                } else {
                                    val srcBitIdx = j
                                    bitVal = (data[srcBitIdx / 8].toInt() shr (7 - (srcBitIdx % 8))) and 1
                                    val destTotalBit = perm.getInt(j)
                                    destByteIdx = destTotalBit / 8
                                    destBitPos = 7 - (destTotalBit % 8)
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
