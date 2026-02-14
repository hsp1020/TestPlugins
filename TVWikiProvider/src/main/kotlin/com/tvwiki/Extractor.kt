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
 * [Version: v1-StrictDebug-BuildFix]
 * 수정 사항:
 * 1. ProxyWebServer 클래스 내 'const val' -> 'val'로 변경하여 빌드 에러 해결.
 * 2. 기존의 모든 로깅 및 로직(Deep Scan, Segment Noise Fix) 100% 유지.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val VER = "[v1-Strict]"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("$VER getUrl() 진입. URL: $url")
        if (proxyServer != null) {
            println("$VER 기존 ProxyServer 중지 시도.")
            proxyServer?.stop()
            proxyServer = null
            println("$VER 기존 ProxyServer 중지 완료.")
        }
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("$VER extract() 시작.")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"
        println("$VER Clean URL: $cleanUrl")

        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        println("$VER isDirectUrl 여부: $isDirectUrl")
        
        if (!isDirectUrl) {
            println("$VER iframe 탐색 로직 진입.")
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("$VER iframe URL 발견: $cleanUrl")
                } else {
                    println("$VER iframe URL 발견 실패.")
                }
            } catch (e: Exception) {
                println("$VER iframe 검색 중 Exception: ${e.message}")
            }
        }

        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null

        try {
            println("$VER WebView 요청 준비.")
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA
            )
            
            println("$VER app.get() 호출 시작.")
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            println("$VER app.get() 완료. 응답 URL: ${response.url}")
            
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
                val cookie = CookieManager.getInstance().getCookie(capturedUrl) ?: ""
                println("$VER c.html 패턴 일치. Cookie 길이: ${cookie.length}")
                
                capturedHeaders = mutableMapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to "https://player.bunny-frame.online/",
                    "Origin" to "https://player.bunny-frame.online",
                    "Cookie" to cookie,
                    "Sec-Ch-Ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to "\"Windows\""
                )
            } else {
                 println("$VER c.html 패턴 불일치.")
            }
        } catch (e: Exception) { 
            println("$VER WebView 로직 중 Exception: ${e.message}")
            e.printStackTrace() 
        }

        if (capturedUrl != null && capturedHeaders != null) {
            try {
                println("$VER M3U8 원본 요청: $capturedUrl")
                val m3u8Res = app.get(capturedUrl, headers = capturedHeaders!!)
                val m3u8Content = m3u8Res.text
                println("$VER M3U8 응답 수신 (Length: ${m3u8Content.length})")

                // IV 추출
                val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(m3u8Content)
                val hexIv = keyMatch?.groupValues?.get(2)
                println("$VER M3U8 파싱된 IV: $hexIv")
                
                val proxy = ProxyWebServer()
                proxy.start()
                proxy.updateSession(capturedHeaders!!, hexIv)
                proxyServer = proxy
                println("$VER ProxyServer 시작됨 (Port: ${proxy.port})")

                val proxyPort = proxy.port
                val proxyRoot = "http://127.0.0.1:$proxyPort"

                val newLines = mutableListOf<String>()
                val lines = m3u8Content.lines()
                val seqMap = ConcurrentHashMap<String, Long>()
                var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
                println("$VER Media Sequence Start: $currentSeq")
                
                val uri = URI(capturedUrl)
                val domain = "${uri.scheme}://${uri.host}"
                val parentUrl = capturedUrl.substringBeforeLast("/")

                for (line in lines) {
                    if (line.startsWith("#EXT-X-KEY")) {
                        println("$VER Original KEY tag removed: $line")
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
                println("$VER Proxy M3U8 생성 완료 (Lines: ${newLines.size})")
                
                val keyUrlMatch = Regex("""URI="([^"]+)"""").find(m3u8Content)
                if (keyUrlMatch != null) {
                    var kUrl = keyUrlMatch.groupValues[1]
                    kUrl = when {
                        kUrl.startsWith("http") -> kUrl
                        kUrl.startsWith("/") -> "$domain$kUrl"
                        else -> "$parentUrl/$kUrl"
                    }
                    proxy.setTargetKeyUrl(kUrl)
                    println("$VER Key URL 설정됨: $kUrl")
                } else {
                    println("$VER Key URL 찾기 실패.")
                }

                callback(
                    newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                println("$VER Callback 호출 완료. 리턴 true.")
                return true

            } catch (e: Exception) {
                println("$VER extract 내부 Exception: ${e.message}")
                e.printStackTrace()
            }
        }
        
        println("$VER capturedUrl 또는 headers 누락으로 실패 리턴.")
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
        
        data class DecryptProfile(val ivMode: Int, val inputOffset: Int, val outputOffset: Int)
        @Volatile private var confirmedProfile: DecryptProfile? = null
        
        // [수정] const 제거 (일반 val로 변경)
        private val VER = "[Bunny-v1-Proxy]"

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) { 
                    println("$VER Server Thread Started on port $port")
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {
                            println("$VER Accept Exception: ${e.message}")
                        } 
                    } 
                }
            } catch (e: Exception) {
                println("$VER Server Start Exception: ${e.message}")
            }
        }

        fun stop() {
            println("$VER Server Stop 호출됨.")
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun updateSession(h: Map<String, String>, iv: String?) {
            currentHeaders = h; playlistIv = iv
            realKey = null
            confirmedProfile = null
            println("$VER Session Updated. IV: $iv")
        }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }
        fun setTargetKeyUrl(url: String) { targetKeyUrl = url }

        private fun ensureKey() {
            if (realKey != null) return
            if (targetKeyUrl == null) {
                println("$VER ensureKey: targetKeyUrl is null")
                return
            }

            runBlocking {
                try {
                    val cleanKeyUrl = targetKeyUrl!!.replace(Regex("[?&]mode=obfuscated"), "")
                    println("$VER Key Download Start: $cleanKeyUrl")
                    val res = app.get(cleanKeyUrl, headers = currentHeaders)
                    var rawData = res.body.bytes()
                    println("$VER Key Download End. Size: ${rawData.size}")

                    if (rawData.size > 100 && rawData[0] == '{'.code.toByte()) {
                        val jsonStr = String(rawData).trim()
                        println("$VER JSON Key Format Detected.")
                        val decrypted = BunnyJsonDecryptor.decrypt(jsonStr)
                        if (decrypted != null) {
                            realKey = decrypted
                            println("$VER Key Decrypt Success. Hex: ${bytesToHex(decrypted, 16)}")
                        } else {
                            println("$VER Key Decrypt Failed (null returned).")
                        }
                    } else if (rawData.size == 16) {
                        realKey = rawData
                        println("$VER Raw 16-byte Key Detected.")
                    } else {
                        println("$VER Unknown Key Format.")
                    }
                } catch (e: Exception) { 
                    println("$VER ensureKey Exception: ${e.message}")
                    e.printStackTrace() 
                }
            }
        }
        
        private fun bytesToHex(bytes: ByteArray, length: Int): String {
            val sb = StringBuilder()
            for (i in 0 until minOf(bytes.size, length)) {
                sb.append(String.format("%02X", bytes[i]))
            }
            return sb.toString()
        }
        
        private fun dumpLargeHex(label: String, bytes: ByteArray) {
            val dumpSize = 3200
            val hexString = bytesToHex(bytes, dumpSize)
            hexString.chunked(3000).forEachIndexed { index, chunk ->
                println("$VER [DUMP] $label [Part $index]: $chunk")
            }
        }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                println("$VER Request: $line")
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
                    println("$VER Playlist Sent.")
                } else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    val seq = seqMap[targetUrl] ?: 0L
                    
                    println("$VER Segment Request: $targetUrl (Seq: $seq)")
                    ensureKey()

                    runBlocking {
                        try {
                            val res = app.get(targetUrl, headers = currentHeaders)
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                println("$VER Segment Downloaded. Size: ${rawData.size}")
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                                if (realKey != null) {
                                    val decrypted = deepScanDecrypt(rawData, realKey!!, seq)
                                    if (decrypted != null) {
                                        println("$VER Decrypt Success (DeepScan). Sending Decrypted Data.")
                                        dumpLargeHex("Decrypted", decrypted)
                                        output.write(decrypted)
                                    } else {
                                        println("$VER Decrypt Failed. Sending Raw Data.")
                                        dumpLargeHex("Raw(Fail)", rawData)
                                        output.write(rawData)
                                    }
                                } else {
                                    println("$VER Key is null. Sending Raw Data.")
                                    dumpLargeHex("Raw(NoKey)", rawData)
                                    output.write(rawData)
                                }
                            } else {
                                println("$VER Segment Download Failed: ${res.code}")
                                output.write("HTTP/1.1 ${res.code} Error\r\n\r\n".toByteArray())
                            }
                        } catch (e: Exception) { 
                            println("$VER Segment Handling Exception: ${e.message}")
                            e.printStackTrace() 
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        private fun deepScanDecrypt(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            println("$VER deepScanDecrypt 진입.")
            
            // 1. 캐시된 프로필 확인
            confirmedProfile?.let { profile ->
                println("$VER Cached Profile Found: IVMode=${profile.ivMode}, InOff=${profile.inputOffset}, OutOff=${profile.outputOffset}")
                val dec = attemptDecrypt(data, key, seq, profile.ivMode, profile.inputOffset)
                if (dec != null) {
                    val startIdx = findSyncByte(dec)
                    if (startIdx != -1) {
                         println("$VER Cached Profile Validated. SyncByte at $startIdx")
                         return dec.copyOfRange(startIdx, dec.size)
                    }
                }
                println("$VER Cached Profile Failed. Resetting.")
                confirmedProfile = null
            }

            // 2. 조합 탐색
            val ivModes = mutableListOf(1, 3, 2, 0) // 1:BE, 3:Zero, 2:LE, 0:Explicit
            println("$VER Brute Force 시작. Modes: $ivModes")
            
            for (inputOffset in 0..15) {
                if (data.size <= inputOffset) break
                val len = data.size - inputOffset
                val alignLen = (len / 16) * 16
                if (alignLen <= 0) continue

                for (ivMode in ivModes) {
                    val dec = attemptDecrypt(data, key, seq, ivMode, inputOffset) ?: continue
                    val outputOffset = findSyncByte(dec)
                    
                    if (outputOffset != -1) {
                        println("$VER Sync Byte Found! InOff:$inputOffset, OutOff:$outputOffset, IVMode:$ivMode")
                        confirmedProfile = DecryptProfile(ivMode, inputOffset, outputOffset)
                        return dec.copyOfRange(outputOffset, dec.size)
                    }
                }
            }
            println("$VER All Combinations Failed.")
            return null
        }

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, seq: Long, ivMode: Int, offset: Int): ByteArray? {
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
            val limit = minOf(data.size, 6400)
            for (i in 0 until limit - 376) {
                if (data[i] == 0x47.toByte() && 
                    data[i+188] == 0x47.toByte() && 
                    data[i+376] == 0x47.toByte()) {
                    return i
                }
            }
            return -1
        }
    }

    object BunnyJsonDecryptor {
        private const val VER = "[Bunny-v1-KeyLogic]"
        
        private fun decodeBase64(input: String): ByteArray {
            return Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        
        private fun bytesToHex(bytes: ByteArray, length: Int): String {
            val sb = StringBuilder()
            for (i in 0 until minOf(bytes.size, length)) {
                sb.append(String.format("%02X", bytes[i]))
            }
            return sb.toString()
        }

        fun decrypt(jsonStr: String): ByteArray? {
            try {
                println("$VER Decrypt Start.")
                val json = JSONObject(jsonStr)
                val encryptedKeyB64 = json.getString("encrypted_key")
                var data = decodeBase64(encryptedKeyB64)
                println("$VER Raw Encrypted Key: ${bytesToHex(data, 16)}...")
                
                val layers = json.getJSONArray("layers")
                var noiseLens: JSONArray? = null
                
                // Pre-scan for noise_lens
                for (i in 0 until layers.length()) {
                    if (layers.getJSONObject(i).getString("name") == "segment_noise") {
                        noiseLens = layers.getJSONObject(i).getJSONArray("noise_lens")
                        break
                    }
                }
                
                for (i in layers.length() - 1 downTo 0) {
                    val layer = layers.getJSONObject(i)
                    val name = layer.getString("name")
                    println("$VER Processing Layer: $name")
                    
                    data = when(name) {
                        "final_encrypt" -> {
                            val mask = layer.getString("xor_mask")
                            val maskBytes = decodeBase64(mask)
                            val res = xor(data, maskBytes)
                            println("$VER After final_encrypt: ${bytesToHex(res, 16)}")
                            res
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
                            val res = buffer.toByteArray()
                            println("$VER After decoy_shuffle: ${bytesToHex(res, 16)}")
                            res
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
                            println("$VER After xor_chain: ${bytesToHex(newData, 16)}")
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
                            println("$VER After sbox: ${bytesToHex(newData, 16)}")
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
                            println("$VER After bit_rotate: ${bytesToHex(newData, 16)}")
                            newData
                        }
                        "segment_noise" -> {
                            val perm = layer.getJSONArray("perm")
                            val noiseL = layer.getJSONArray("noise_lens")
                            
                            var currentReadOffset = 0
                            val result = ByteArray(perm.length())
                            
                            for (j in 0 until perm.length()) {
                                val originalIndex = perm.getInt(j) 
                                val chunkLen = noiseL.getInt(originalIndex) 
                                
                                if (currentReadOffset < data.size) {
                                    result[originalIndex] = data[currentReadOffset]
                                }
                                currentReadOffset += chunkLen
                            }
                            println("$VER After segment_noise: ${bytesToHex(result, 16)}")
                            result
                        }
                        "bit_interleave" -> {
                            val perm = layer.getJSONArray("perm")
                            val newData = ByteArray(16) 
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
                            println("$VER After bit_interleave: ${bytesToHex(newData, 16)}")
                            newData
                        }
                        else -> data
                    }
                }
                return data
            } catch (e: Exception) {
                println("$VER Decrypt Exception: ${e.message}")
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
