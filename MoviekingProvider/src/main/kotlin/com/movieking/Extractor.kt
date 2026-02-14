/**
 * v124-4: Sequence Persistence & Anti-Blocking
 * [수정 사항]
 * 1. SeqMap 영속성: 비디오 ID가 같으면 seqMap을 초기화하지 않고 누적하여 IV 불일치 방지.
 * 2. BruteForce 차단: confirmedKey가 존재하면 절대로 bruteForce를 돌리지 않고 즉시 대기/반환.
 * 3. 병렬 처리 최적화: 구간 이동 시 몰리는 요청이 서로를 블로킹하지 않도록 수정.
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(playlistRes)
            val hexIv = keyMatch?.groupValues?.get(2)
            val candidates = if (keyMatch != null) solveKeyCandidatesCombinatorial(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            
            if (proxyServer == null || !proxyServer!!.isActive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            // v124-4: 세션 업데이트 시 seqMap 누적 유지
            proxyServer!!.updateSession(videoId, baseHeaders, hexIv, candidates)
            
            val lines = playlistRes.lines()
            val newLines = mutableListOf<String>()
            val currentSeqHeader = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(playlistRes)?.groupValues?.get(1)?.toLong() ?: 0L
            var tempSeq = currentSeqHeader
            
            val proxyRoot = "http://127.0.0.1:${proxyServer!!.port}/$videoId"
            val newSeqMap = ConcurrentHashMap<String, Long>()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) continue
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "${m3u8Url.substringBeforeLast("/")}/$line"
                    newSeqMap[segmentUrl] = tempSeq
                    newLines.add("$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}")
                    tempSeq++
                } else newLines.add(line)
            }
            
            proxyServer!!.setPlaylist(newLines.joinToString("\n"))
            proxyServer!!.mergeSeqMap(newSeqMap) // 덮어쓰기가 아닌 병합
            
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v124-4] Error: $e") }
    }

    private fun extractVideoIdDeep(url: String): String {
        return try {
            val token = url.split("/v1/").getOrNull(1)?.split(".")?.getOrNull(1)
            val decoded = String(Base64.decode(token!!, Base64.URL_SAFE))
            Regex(""""id"\s*:\s*(\d+)""").find(decoded)?.groupValues?.get(1) ?: "ID_ERR"
        } catch (e: Exception) { "ID_ERR" }
    }

    private suspend fun solveKeyCandidatesCombinatorial(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val b64 = Base64.decode(encStr, Base64.DEFAULT)
            if (b64.size == 16) list.add(b64)
            if (b64.size >= 22) {
                val segs = mutableListOf<ByteArray>()
                var idx = 0
                val gaps = listOf(0, 2, 2, 2, 2)
                for (i in 0..3) {
                    idx += gaps[i]
                    segs.add(b64.copyOfRange(idx, idx + 4))
                    idx += 4
                }
                generatePermutations(listOf(0, 1, 2, 3)).forEach { p ->
                    val k = ByteArray(16)
                    for (j in 0..3) System.arraycopy(segs[p[j]], 0, k, j * 4, 4)
                    list.add(k)
                }
            }
        } catch (e: Exception) {}
        return list.distinctBy { it.contentHashCode() }
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

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var lastVideoId: String? = null
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var keyCandidates: List<ByteArray> = emptyList()
        private var seqMap = ConcurrentHashMap<String, Long>()
        @Volatile private var confirmedKey: ByteArray? = null
        @Volatile private var confirmedIvType: Int = -1

        fun isActive() = isRunning && serverSocket?.isClosed == false
        fun start() {
            serverSocket = ServerSocket(0).apply { port = localPort }
            isRunning = true
            thread(isDaemon = true) { while (isRunning) try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
        }
        fun stop() { isRunning = false; serverSocket?.close() }
        fun updateSession(vId: String, h: Map<String, String>, iv: String?, k: List<ByteArray>) {
            currentHeaders = h; playlistIv = iv; keyCandidates = k
            if (lastVideoId != vId) {
                confirmedKey = null; confirmedIvType = -1
                seqMap.clear()
                lastVideoId = vId
            }
        }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun mergeSeqMap(newMap: Map<String, Long>) { seqMap.putAll(newMap) }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 15000
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                    output.write(currentPlaylist.toByteArray())
                } else if (path.contains("/proxy")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    val seq = seqMap[targetUrl] ?: 0L
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders, timeout = 20)
                        if (res.isSuccessful) {
                            val raw = res.body.bytes()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (raw.isNotEmpty() && raw[0] == 0x47.toByte()) output.write(raw)
                            else {
                                // v124-4: 캐시된 키가 있으면 브루트포스 절대 금지 (병목 제거)
                                confirmedKey?.let { k ->
                                    decryptDirect(raw, k, confirmedIvType, seq)?.let { 
                                        output.write(it); return@runBlocking 
                                    }
                                }
                                bruteForceCombinatorial(raw, seq)?.let { output.write(it) } ?: output.write(raw)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { socket.close() }
        }

        private fun decryptDirect(data: ByteArray, key: ByteArray, ivType: Int, seq: Long): ByteArray? {
            return try {
                val iv = getIv(ivType, seq)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data).takeIf { it.isNotEmpty() && it[0] == 0x47.toByte() }
            } catch (e: Exception) { null }
        }

        private fun bruteForceCombinatorial(data: ByteArray, seq: Long): ByteArray? {
            if (data.size < 376) return null
            val ivs = getIvList(seq)
            for ((kIdx, key) in keyCandidates.withIndex()) {
                for ((iIdx, iv) in ivs.withIndex()) {
                    try {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        val head = cipher.update(data.take(376).toByteArray())
                        if (head.size >= 189 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte()) {
                            confirmedKey = key; confirmedIvType = iIdx
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                            return cipher.doFinal(data)
                        }
                    } catch (e: Exception) {}
                }
            }
            return null
        }

        private fun getIvList(seq: Long): List<ByteArray> {
            val list = mutableListOf<ByteArray>()
            playlistIv?.let { pIv ->
                try {
                    val iv = ByteArray(16)
                    pIv.removePrefix("0x").chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                    list.add(iv)
                } catch(e: Exception) {}
            }
            val sIv = ByteArray(16)
            for (i in 0..7) sIv[15 - i] = (seq shr (i * 8)).toByte()
            list.add(sIv); list.add(ByteArray(16))
            return list
        }
        private fun getIv(type: Int, seq: Long) = getIvList(seq).let { if (type in it.indices) it[type] else ByteArray(16) }
    }
}
