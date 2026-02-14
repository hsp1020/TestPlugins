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
 * [Version: v1]
 * 사용자 요청:
 * 1. 모든 로그 태그에 'v1' 포함 ([Bunny-v1]).
 * 2. 모든 실행 과정(Step)을 로그로 출력.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v1]"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("$TAG getUrl 호출됨. URL: $url")
        proxyServer?.stop()
        println("$TAG 기존 프록시 서버 중지됨.")
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
        println("$TAG extract 시작. 원본 URL: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"
        println("$TAG Clean URL: $cleanUrl")

        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            println("$TAG Direct URL 아님. iframe 검색 시도.")
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("$TAG iframe URL 발견: $cleanUrl")
                } else {
                    println("$TAG iframe URL 발견 실패.")
                }
            } catch (e: Exception) {
                println("$TAG iframe 검색 중 오류: ${e.message}")
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
            println("$TAG WebView 요청 시작.")
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA
            )
            
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            println("$TAG WebView 응답 URL: ${response.url}")
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
                val cookie = CookieManager.getInstance().getCookie(capturedUrl) ?: ""
                println("$TAG c.html 발견. 쿠키 확보: ${cookie.take(20)}...")
                
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
                 println("$TAG c.html 패턴 매칭 실패.")
            }
        } catch (e: Exception) { 
            println("$TAG WebView 요청 중 예외 발생: ${e.message}")
            e.printStackTrace() 
        }

        if (capturedUrl != null && capturedHeaders != null) {
            try {
                println("$TAG M3U8 요청 시작: $capturedUrl")
                val m3u8Res = app.get(capturedUrl, headers = capturedHeaders!!)
                val m3u8Content = m3u8Res.text
                println("$TAG M3U8 내용 확보 완료 (길이: ${m3u8Content.length})")

                // IV 추출
                val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(m3u8Content)
                val hexIv = keyMatch?.groupValues?.get(2)
                println("$TAG M3U8 IV 추출 결과: $hexIv")
                
                val proxy = ProxyWebServer()
                proxy.start()
                proxy.updateSession(capturedHeaders!!, hexIv)
                proxyServer = proxy
                println("$TAG 프록시 서버 시작됨 (Port: ${proxy.port})")

                val proxyPort = proxy.port
                val proxyRoot = "http://127.0.0.1:$proxyPort"

                val newLines = mutableListOf<String>()
                val lines = m3u8Content.lines()
                val seqMap = ConcurrentHashMap<String, Long>()
                var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
                println("$TAG 미디어 시퀀스 시작 번호: $currentSeq")
                
                val uri = URI(capturedUrl)
                val domain = "${uri.scheme}://${uri.host}"
                val parentUrl = capturedUrl.substringBeforeLast("/")

                for (line in lines) {
                    if (line.startsWith("#EXT-X-KEY")) {
                        println("$TAG 원본 KEY 태그 제거됨: $line")
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
                    println("$TAG 키 URL 설정됨: $kUrl")
                } else {
                    println("$TAG M3U8에서 키 URI를 찾을 수 없음.")
                }

                callback(
                    newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                println("$TAG ExtractorLink 콜백 호출 완료.")
                return true

            } catch (e: Exception) {
                println("$TAG Extract 처리 중 오류: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("$TAG capturedUrl 또는 headers가 null임. 추출 실패.")
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
        
        data class DecryptProfile(val ivMode: Int, val padding: String, val offset: Int)
        @Volatile private var confirmedProfile: DecryptProfile? = null

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) { 
                    println("[Bunny-v1] ProxyServer Thread 시작.")
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {
                            println("[Bunny-v1] Socket Accept Error: ${e.message}")
                        } 
                    } 
                }
            } catch (e: Exception) {
                println("[Bunny-v1] ProxyServer Start Error: ${e.message}")
            }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun updateSession(h: Map<String, String>, iv: String?) {
            currentHeaders = h; playlistIv = iv
            realKey = null
            confirmedProfile = null
            println("[Bunny-v1] 세션 업데이트됨. IV: $iv")
        }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }
        fun setTargetKeyUrl(url: String) { targetKeyUrl = url }

        private fun ensureKey() {
            if (realKey != null) return
            if (targetKeyUrl == null) {
                println("[Bunny-v1] Key URL이 설정되지 않음.")
                return
            }

            runBlocking {
                try {
                    val cleanKeyUrl = targetKeyUrl!!.replace(Regex("[?&]mode=obfuscated"), "")
                    println("[Bunny-v1] 키 다운로드 시도: $cleanKeyUrl")
                    val res = app.get(cleanKeyUrl, headers = currentHeaders)
                    var rawData = res.body.bytes()
                    println("[Bunny-v1] 키 데이터 수신됨. 크기: ${rawData.size} bytes")

                    if (rawData.size > 100 && rawData[0] == '{'.code.toByte()) {
                        println("[Bunny-v1] JSON 키 포맷 감지됨. 복호화 시작.")
                        val jsonStr = String(rawData).trim()
                        val decrypted = BunnyJsonDecryptor.decrypt(jsonStr)
                        if (decrypted != null) {
                            realKey = decrypted
                            println("[Bunny-v1] 키 복호화 성공. Hex: ${bytesToHex(decrypted, 16)}")
                        } else {
                            println("[Bunny-v1] 키 복호화 실패.")
                        }
                    } else if (rawData.size == 16) {
                        realKey = rawData
                        println("[Bunny-v1] 16바이트 Raw 키 확인됨.")
                    } else {
                        println("[Bunny-v1] 알 수 없는 키 포맷. 크기: ${rawData.size}")
                    }
                } catch (e: Exception) {
                    println("[Bunny-v1] 키 확보 중 오류: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        
        private fun bytesToHex(bytes: ByteArray, length: Int): String {
            val sb = StringBuilder()
            val len = minOf(bytes.size, length)
            for (i in 0 until len) {
                sb.append(String.format("%02X", bytes[i]))
            }
            return sb.toString()
        }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                println("[Bunny-v1] 요청 수신: $line")
                
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
                    println("[Bunny-v1] Playlist 제공 완료.")
                } else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    val seq = seqMap[targetUrl] ?: 0L
                    
                    println("[Bunny-v1] Segment 요청: $targetUrl (Seq: $seq)")
                    ensureKey()

                    runBlocking {
                        try {
                            val res = app.get(targetUrl, headers = currentHeaders)
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                println("[Bunny-v1] Segment 다운로드 완료. 크기: ${rawData.size}")
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                                if (realKey != null) {
                                    println("[Bunny-v1] 복호화 시도 시작. Seq: $seq")
                                    val decrypted = bruteForceDecrypt(rawData, realKey!!, seq)
                                    if (decrypted != null) {
                                        println("[Bunny-v1] 복호화 데이터 전송 (성공).")
                                        output.write(decrypted)
                                    } else {
                                        println("[Bunny-v1] 복호화 실패. 원본 데이터 전송.")
                                        output.write(rawData)
                                    }
                                } else {
                                    println("[Bunny-v1] 키 없음. 원본 데이터 전송.")
                                    output.write(rawData)
                                }
                            } else {
                                println("[Bunny-v1] Segment 다운로드 실패: ${res.code}")
                                output.write("HTTP/1.1 ${res.code} Error\r\n\r\n".toByteArray())
                            }
                        } catch (e: Exception) {
                            println("[Bunny-v1] Segment 처리 중 예외: ${e.message}")
                            e.printStackTrace() 
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { 
                 println("[Bunny-v1] Client Handle Error: ${e.message}")
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        private fun bruteForceDecrypt(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            // 1. 캐시된 프로필 시도
            confirmedProfile?.let { profile ->
                println("[Bunny-v1] 캐시된 프로필 사용: Mode=${profile.ivMode}, Pad=${profile.padding}, Offset=${profile.offset}")
                val dec = attemptDecrypt(data, key, seq, profile.ivMode, profile.padding, profile.offset)
                if (isValidTS(dec)) return dec
                println("[Bunny-v1] 캐시 프로필 실패. 재탐색 시작.")
                confirmedProfile = null
            }

            // 2. 조합 탐색
            val ivModes = mutableListOf(1, 2, 3, 4, 5) // 1:BE, 2:LE, 3:Zero, 4:BE(Start), 5:LE(Start)
            if (!playlistIv.isNullOrEmpty()) ivModes.add(0, 0)
            
            val paddings = listOf("PKCS5Padding", "NoPadding")
            val offsets = listOf(0, 1, 2, 3, 4) // 앞부분 0~4 바이트 잘라보기

            for (offset in offsets) {
                for (ivMode in ivModes) {
                    for (padding in paddings) {
                        try {
                            val dec = attemptDecrypt(data, key, seq, ivMode, padding, offset)
                            if (isValidTS(dec)) {
                                println("[Bunny-v1] Crack Success! IV Mode: $ivMode, Padding: $padding, Offset: $offset")
                                confirmedProfile = DecryptProfile(ivMode, padding, offset)
                                return dec
                            }
                        } catch (e: Exception) {
                            // Decrypt Error (Bad Padding etc) - Log optional
                        }
                    }
                }
            }
            println("[Bunny-v1] 모든 조합 실패.")
            return null
        }

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, seq: Long, ivMode: Int, padding: String, offset: Int): ByteArray? {
            if (offset >= data.size) return null
            val inputData = if (offset > 0) data.copyOfRange(offset, data.size) else data
            
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
                    3 -> {} // Zero IV
                    4 -> ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN).putLong(0, seq)
                    5 -> ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN).putLong(0, seq)
                }

                val cipher = Cipher.getInstance("AES/CBC/$padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(inputData)
            } catch (e: Exception) { null }
        }

        private fun isValidTS(data: ByteArray?): Boolean {
            if (data == null || data.size < 376) return false
            // Check first few packets for sync byte 0x47
            val sync1 = data[0] == 0x47.toByte()
            val sync2 = data[188] == 0x47.toByte()
            return sync1 && sync2
        }
    }

    object BunnyJsonDecryptor {
        private const val TAG = "[Bunny-v1]"
        private fun decodeBase64(input: String): ByteArray {
            return Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        
        private fun bytesToHex(bytes: ByteArray, length: Int): String {
            val sb = StringBuilder()
            val len = minOf(bytes.size, length)
            for (i in 0 until len) {
                sb.append(String.format("%02X", bytes[i]))
            }
            return sb.toString()
        }

        fun decrypt(jsonStr: String): ByteArray? {
            try {
                println("$TAG JSON 복호화 시작.")
                val json = JSONObject(jsonStr)
                val encryptedKeyB64 = json.getString("encrypted_key")
                var data = decodeBase64(encryptedKeyB64)
                println("$TAG Step 0 (Raw): ${bytesToHex(data, 16)}...")
                
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
                    println("$TAG Layer 처리 시작: $name")
                    
                    data = when(name) {
                        "final_encrypt" -> {
                            val mask = layer.getString("xor_mask")
                            val maskBytes = decodeBase64(mask)
                            val res = xor(data, maskBytes)
                            println("$TAG $name 완료. 결과: ${bytesToHex(res, 8)}...")
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
                            println("$TAG $name 완료. 결과: ${bytesToHex(res, 8)}...")
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
                            println("$TAG $name 완료. 결과: ${bytesToHex(newData, 8)}...")
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
                            println("$TAG $name 완료. 결과: ${bytesToHex(newData, 8)}...")
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
                            println("$TAG $name 완료. 결과: ${bytesToHex(newData, 8)}...")
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
                            println("$TAG $name 완료. 결과: ${bytesToHex(result, 8)}...")
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
                            println("$TAG $name 완료. 결과: ${bytesToHex(newData, 8)}...")
                            newData
                        }
                        else -> data
                    }
                }
                return data
            } catch (e: Exception) {
                println("$TAG JSON 복호화 중 예외: ${e.message}")
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
