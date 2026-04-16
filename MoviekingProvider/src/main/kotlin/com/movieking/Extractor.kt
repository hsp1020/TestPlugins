// v2.0
package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v2.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("[BcbcRedExtractor Debug v2.0] getUrl 실행: url=$url")
        try {
            val videoId = extractVideoIdDeep(url)
            val uri = URI(url)
            val hostUrl = "${uri.scheme}://${uri.host}"
            val baseHeaders = mutableMapOf("Referer" to "$hostUrl/", "Origin" to hostUrl, "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            println("[BcbcRedExtractor Debug v2.0] 추출된 m3u8Url=$m3u8Url")
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            
            // [고유 개선] 무거운 CPU 연산을 백그라운드 코루틴으로 분리하여 ANR(UI 멈춤) 방지
            val candidates = if (keyMatch != null) withContext(Dispatchers.Default) { solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1]) } else emptyList()
            println("[BcbcRedExtractor Debug v2.0] 복호화 키 후보군 수=${candidates.size}")
            
            val lines = playlistRes.lines()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            var firstSegmentUrl: String? = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let { if (it.startsWith("http")) it else "${m3u8Url.substringBeforeLast("/")}/$it" }

            var confirmedKey = ByteArray(16)
            var confirmedIvType = -1

            if (firstSegmentUrl != null && candidates.isNotEmpty()) {
                try {
                    println("[BcbcRedExtractor Debug v2.0] 첫 번째 세그먼트로 키 검증 시작: $firstSegmentUrl")
                    val firstSegBytes = app.get(firstSegmentUrl, headers = baseHeaders).body.bytes()
                    val ivs = getIvList(currentSeq, hexIv)
                    val checkSize = Math.min(firstSegBytes.size, 188 * 2)
                    
                    outer@ for ((keyIdx, key) in candidates.withIndex()) {
                        for ((ivIdx, iv) in ivs.withIndex()) {
                            try {
                                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                val head = cipher.update(firstSegBytes.take(checkSize).toByteArray())
                                if (head.isNotEmpty() && head[0] == 0x47.toByte() && head.size > 188 && head[188] == 0x47.toByte()) {
                                    confirmedKey = key; confirmedIvType = ivIdx
                                    println("[BcbcRedExtractor Debug v2.0] 일치하는 키 발견: keyIdx=$keyIdx, ivIdx=$ivIdx")
                                    break@outer
                                }
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    println("[BcbcRedExtractor Debug v2.0] 키 검증 중 예외 발생: ${e.message}")
                }
            }

            // 프록시 웹서버 대신, 확인된 키를 Data URI 형식(Base64)으로 직접 인코딩
            val base64Key = Base64.encodeToString(confirmedKey, Base64.NO_WRAP)
            val dataUriKey = "data:application/octet-stream;base64,$base64Key"
            println("[BcbcRedExtractor Debug v2.0] 확정된 키를 Data URI 형식으로 변환 완료")

            val newLines = mutableListOf<String>()
            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    var newKeyLine = """#EXT-X-KEY:METHOD=AES-128,URI="$dataUriKey""""
                    if (confirmedIvType == 0 && hexIv != null) newKeyLine += """,IV=$hexIv"""
                    else if (confirmedIvType == 2) newKeyLine += """,IV=0x00000000000000000000000000000000"""
                    newLines.add(newKeyLine)
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    // TS 세그먼트 경로를 절대 경로로 변환
                    val absoluteLine = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    newLines.add(absoluteLine)
                } else newLines.add(line)
            }
            
            // 재생 목록 전체를 Data URI 형식으로 변환하여 Cloudstream 네이티브 플레이어 및 다운로더에 전달
            val finalM3u8 = newLines.joinToString("\n")
            val base64M3u8 = Base64.encodeToString(finalM3u8.toByteArray(charset("UTF-8")), Base64.NO_WRAP)
            val finalDataUriM3u8 = "data:application/x-mpegURL;base64,$base64M3u8"
            println("[BcbcRedExtractor Debug v2.0] 최종 M3U8을 Data URI로 생성 완료, 콜백 전달")
            
            callback(newExtractorLink(name, name, finalDataUriM3u8, ExtractorLinkType.M3U8) { this.referer = "$hostUrl/" })
        } catch (e: Exception) {
            println("[BcbcRedExtractor Debug v2.0] getUrl 전체 처리 중 예외 발생: ${e.message}")
        }
    }

    private fun extractVideoIdDeep(url: String): String {
        try {
            val parts = url.split(Regex("/v\\d+/"))
            if (parts.size >= 2) {
                val payload = parts[1].split(".").getOrNull(1)
                if (payload != null) {
                    val decoded = String(Base64.decode(payload, Base64.URL_SAFE))
                    val idMatch = Regex(""""id"\s*:\s*(\d+)""").find(decoded)
                    if (idMatch != null) return idMatch.groupValues[1]
                }
            }
        } catch (e: Exception) {}
        return "ID_ERR"
    }

    private fun getIvList(seq: Long, hexIv: String?): List<ByteArray> {
        val ivs = mutableListOf<ByteArray>()
        if (!hexIv.isNullOrEmpty()) {
            try {
                val hex = hexIv.removePrefix("0x")
                val iv = ByteArray(16)
                hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                ivs.add(iv)
            } catch(e:Exception) { ivs.add(ByteArray(16)) }
        } else ivs.add(ByteArray(16))
        
        val seqIv = ByteArray(16)
        for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
        ivs.add(seqIv)
        ivs.add(ByteArray(16))
        return ivs
    }

    // [고유 개선] 하드코딩된 암호화 갭 배열을 다중 패턴으로 유연화하여, 사이트 방어 대응력 확보
    private suspend fun solveKeyCandidatesCombinatorial(h: Map<String, String>, kUrl: String): List<ByteArray> {
        println("[BcbcRedExtractor Debug v2.0] solveKeyCandidatesCombinatorial 실행: kUrl=$kUrl")
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }

            if (b64.size == 16) list.add(b64)
            if (b64.size >= 22) { 
                val src = b64
                val targetGapsList = listOf(listOf(0, 2, 2, 2, 2), listOf(0, 1, 1, 1, 1))
                val allPerms = generatePermutations(listOf(0, 1, 2, 3))

                for (gaps in targetGapsList) {
                    try {
                        val segs = mutableListOf<ByteArray>()
                        var idx = gaps[0]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[1]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[2]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[3]
                        segs.add(src.copyOfRange(idx, idx + 4))

                        for (perm in allPerms) {
                            val k = ByteArray(16)
                            for (j in 0 until 4) System.arraycopy(segs[perm[j]], 0, k, j * 4, 4)
                            list.add(k)
                        }
                    } catch (e: Exception) {}
                }
            }
            return list.distinctBy { it.contentHashCode() }
        } catch (e: Exception) { return emptyList() }
    }

    private fun generatePermutations(list: List<Int>): List<List<Int>> {
        if (list.isEmpty()) return listOf(emptyList())
        val result = mutableListOf<List<Int>>()
        for (i in list.indices) {
            val elem = list[i]
            val rest = list.take(i) + list.drop(i + 1)
            for (p in generatePermutations(rest)) result.add(listOf(elem) + p)
        }
        return result
    }
}
