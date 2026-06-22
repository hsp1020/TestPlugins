package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LinkkfExtractor {
    private val SUBTITLE_BASE_URL = "https://k1.sub1.top/s"

    data class LinkkfResult(val m3u8Url: String, val subtitleUrl: String, val needsWebView: Boolean = false)

    suspend fun extract(url: String, referer: String): LinkkfResult? {
        try {
            val doc = app.get(url, headers = mapOf("Referer" to referer)).document
            val scriptContent = doc.select("script").find { it.data().contains("var player_") && it.data().contains("actual_url") }?.data() ?: return null

            val startIndex = scriptContent.indexOf("{")
            val endIndex = scriptContent.lastIndexOf("}")
            if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) return null

            val jsonString = scriptContent.substring(startIndex, endIndex + 1)

            val playerData = mapper.readValue(jsonString, object : TypeReference<PlayerData>() {})
            
            val actualUrl = playerData.actualUrl ?: return null
            val rawSubUrl = if (playerData.url != null) "$SUBTITLE_BASE_URL/${playerData.url}.vtt" else ""

            // [로컬 프록시 적용] 플레이어(OkHttp)가 정상 처리할 수 있는 로컬 http 주소로 가공하여 전달
            val finalSubtitleUrl = if (rawSubUrl.isNotEmpty()) {
                println("[Linkkf-SubtitleProxy] [extract] 원본 자막 발견됨: $rawSubUrl")
                val proxyUrl = SubtitleProxy.getProxyUrl(rawSubUrl)
                println("[Linkkf-SubtitleProxy] [extract] 생성된 프록시 로컬 자막 URL: $proxyUrl")
                proxyUrl
            } else {
                println("[Linkkf-SubtitleProxy] [extract] 발견된 자막 파일이 없습니다.")
                ""
            }

            if (actualUrl.contains(".m3u8")) return LinkkfResult(actualUrl, finalSubtitleUrl, needsWebView = false)
            return LinkkfResult(actualUrl, finalSubtitleUrl, needsWebView = true)
        } catch (e: Exception) { 
            println("[Linkkf-SubtitleProxy] [extract] 추출 단계 예외 발생: ${e.message}")
            return null 
        }
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}

/**
 * [표준 순수 소켓 버전 - 정밀 정규식] 외부 라이브러리 및 com.sun 의존성이 없는 완전 순수 자막 프록시 서버
 */
object SubtitleProxy {
    private var serverSocket: ServerSocket? = null
    private var executor: ScheduledExecutorService? = null
    private var port: Int = 0
    private var shutdownTask: ScheduledFuture<*>? = null
    private val lock = Any()
    private var isRunning = false

    private const val IDLE_TIMEOUT_SEC = 120L

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://linkkf.tv/"
    )

    fun getProxyUrl(originalUrl: String): String {
        synchronized(lock) {
            println("[Linkkf-SubtitleProxy] [getProxyUrl] 요청 수신됨. 원본 URL: $originalUrl")
            ensureServerRunning()
            resetShutdownTimer()
        }
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
        return "http://127.0.0.1:$port/sub?url=$encodedUrl"
    }

    private fun ensureServerRunning() {
        if (serverSocket == null || !isRunning) {
            println("[Linkkf-SubtitleProxy] [ensureServerRunning] 소켓 서버가 꺼져 있습니다. 기동을 시작합니다...")
            try {
                val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                port = ss.localPort
                isRunning = true
                println("[Linkkf-SubtitleProxy] [ensureServerRunning] ServerSocket 성공적으로 바인딩됨. 할당 포트: $port")

                val exec = Executors.newScheduledThreadPool(1) { runnable ->
                    Thread(runnable, "Linkkf-SubtitleProxy-Worker").apply {
                        isDaemon = true
                    }
                }
                executor = exec

                // [데드락 해결] 소켓 수신 대기를 담당하는 별도의 독립 데몬 스레드 구동
                Thread({
                    println("[Linkkf-SubtitleProxy] [ServerLoop] 전용 수신 스레드 구동을 시작합니다.")
                    while (isRunning) {
                        try {
                            val clientSocket = ss.accept()
                            println("[Linkkf-SubtitleProxy] [ServerLoop] 클라이언트 연결 수립됨: ${clientSocket.remoteSocketAddress}")
                            exec.execute {
                                handleClient(clientSocket)
                            }
                        } catch (e: Exception) {
                            println("[Linkkf-SubtitleProxy] [ServerLoop] Accept 중단됨 (서버 종료 혹은 소켓 해제): ${e.message}")
                            break
                        }
                    }
                }, "Linkkf-SubtitleProxy-Acceptor").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                println("[Linkkf-SubtitleProxy] [ensureServerRunning] 서버 시작 실패: ${e.message}")
            }
        } else {
            println("[Linkkf-SubtitleProxy] [ensureServerRunning] 서버가 이미 가동 중입니다. (포트: $port)")
        }
    }

    private fun handleClient(socket: Socket) {
        synchronized(lock) {
            resetShutdownTimer() 
        }

        println("[Linkkf-SubtitleProxy] [handleClient] 클라이언트 요청 처리 시작. 스레드: ${Thread.currentThread().name}")
        var reader: BufferedReader? = null
        var output: OutputStream? = null
        try {
            socket.soTimeout = 5000 
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            output = socket.getOutputStream()

            val firstLine = reader.readLine()
            println("[Linkkf-SubtitleProxy] [handleClient] HTTP 요청 라인: $firstLine")
            if (firstLine == null) return
            
            val parts = firstLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                println("[Linkkf-SubtitleProxy] [handleClient] 잘못된 요청 타입 (GET 아님): $firstLine")
                sendErrorResponse(output, 400, "Bad Request")
                return
            }

            val pathWithQuery = parts[1]
            if (!pathWithQuery.startsWith("/sub")) {
                println("[Linkkf-SubtitleProxy] [handleClient] 잘못된 엔드포인트 접근: $pathWithQuery")
                sendErrorResponse(output, 404, "Not Found")
                return
            }

            val queryIdx = pathWithQuery.indexOf("?")
            if (queryIdx == -1) {
                println("[Linkkf-SubtitleProxy] [handleClient] 파라미터가 누락되었습니다: $pathWithQuery")
                sendErrorResponse(output, 400, "Missing URL Parameter")
                return
            }

            val query = pathWithQuery.substring(queryIdx + 1)
            var originalUrl = ""
            val pairs = query.split("&")
            for (pair in pairs) {
                val idx = pair.indexOf("=")
                if (idx > 0 && URLDecoder.decode(pair.substring(0, idx), "UTF-8") == "url") {
                    originalUrl = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    break
                }
            }

            if (originalUrl.isEmpty()) {
                println("[Linkkf-SubtitleProxy] [handleClient] 디코딩된 원본 자막 URL이 비어있습니다.")
                sendErrorResponse(output, 400, "Empty URL")
                return
            }

            println("[Linkkf-SubtitleProxy] [handleClient] 원본 자막 다운로드 시도 중... URL: $originalUrl")
            
            val rawVtt = kotlinx.coroutines.runBlocking {
                val res = app.get(url = originalUrl, headers = headers)
                println("[Linkkf-SubtitleProxy] [handleClient] 자막 원격 서버 응답 완료. HTTP 상태 코드: ${res.code}")
                res.text
            }

            println("[Linkkf-SubtitleProxy] [handleClient] 자막 다운로드 성공. 원본 크기: ${rawVtt.length} 글자")
            println("[Linkkf-SubtitleProxy] [handleClient] 실시간 자막 가공(Sanitization) 엔진을 구동합니다...")
            val sanitizedVtt = sanitizeWebVtt(rawVtt, originalUrl)
            val responseBytes = sanitizedVtt.toByteArray(StandardCharsets.UTF_8)
            println("[Linkkf-SubtitleProxy] [handleClient] 가공 완료. 최종 전송 크기: ${responseBytes.size} 바이트")

            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 200 OK\r\n")
            headerBuilder.append("Content-Type: text/vtt; charset=utf-8\r\n")
            headerBuilder.append("Content-Length: ${responseBytes.size}\r\n")
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
            headerBuilder.append("Connection: close\r\n")
            headerBuilder.append("\r\n")

            println("[Linkkf-SubtitleProxy] [handleClient] 클라이언트 소켓에 HTTP 200 응답 쓰기 시작...")
            output.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
            output.write(responseBytes)
            output.flush()
            println("[Linkkf-SubtitleProxy] [handleClient] 클라이언트 소켓 전송 성공 및 연결 종료 완료.")
        } catch (e: Exception) {
            println("[Linkkf-SubtitleProxy] [handleClient] 요청 처리 중 치명적 예외 발생: ${e.message}")
            e.printStackTrace()
            try {
                output?.let { sendErrorResponse(it, 500, "Internal Server Error") }
            } catch (ex: Exception) {}
        } finally {
            try { reader?.close() } catch (e: Exception) {}
            try { output?.close() } catch (e: Exception) {}
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun sendErrorResponse(output: OutputStream, statusCode: Int, message: String) {
        try {
            val responseBytes = message.toByteArray(StandardCharsets.UTF_8)
            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 $statusCode\r\n")
            headerBuilder.append("Content-Type: text/plain; charset=utf-8\r\n")
            headerBuilder.append("Content-Length: ${responseBytes.size}\r\n")
            headerBuilder.append("Connection: close\r\n")
            headerBuilder.append("\r\n")

            output.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
            output.write(responseBytes)
            output.flush()
            println("[Linkkf-SubtitleProxy] [sendErrorResponse] 에러 전송 완료 ($statusCode: $message)")
        } catch (e: Exception) {}
    }

    private fun resetShutdownTimer() {
        shutdownTask?.cancel(false)
        shutdownTask = executor?.schedule({
            synchronized(lock) {
                println("[Linkkf-SubtitleProxy] [Timer] 120초 동안 무활동이 감지되어 자동 서버 종료 절차를 시작합니다.")
                stopServer()
            }
        }, IDLE_TIMEOUT_SEC, TimeUnit.SECONDS)
        println("[Linkkf-SubtitleProxy] [Timer] 자동 종료 타이머 리셋 완료. (유휴 제한 시간: $IDLE_TIMEOUT_SEC 초)")
    }

    private fun stopServer() {
        println("[Linkkf-SubtitleProxy] [stopServer] 프록시 서버 리소스 완전히 정리 시작...")
        isRunning = false
        try {
            serverSocket?.close()
            println("[Linkkf-SubtitleProxy] [stopServer] ServerSocket 성공적으로 닫힘.")
        } catch (e: Exception) {}
        try {
            executor?.shutdownNow()
            println("[Linkkf-SubtitleProxy] [stopServer] 데몬 스레드 풀 강제 폐기됨.")
        } catch (e: Exception) {}
        
        serverSocket = null
        executor = null
        port = 0
        shutdownTask = null
        println("[Linkkf-SubtitleProxy] [stopServer] 리소스 정리 절차 완료.")
    }

    private fun sanitizeWebVtt(rawVtt: String, fallbackUrl: String): String {
        // [치명적 인코딩 치료] 시작태그만 &lt;rt&gt;로 이스케이프 처리된 비표준 문법을 치료합니다.
        var processed = rawVtt
            .replace("&lt;", "<")
            .replace("&gt;", ">")

        if (!processed.contains("ruby", ignoreCase = true) && 
            !processed.contains("rt", ignoreCase = true) && 
            !processed.contains("<v", ignoreCase = true)) {
            println("[Linkkf-SubtitleProxy] [sanitizeWebVtt] 루비 및 화자 태그가 본문에 없음. 무삭제 고속 패스 발동.")
            return processed
        }

        var rubyCount = 0
        var rtCount = 0
        var vCount = 0

        val processedResult = processed.lineSequence().map { line ->
            if (!line.contains("<") || 
                (!line.contains("ruby", ignoreCase = true) && 
                 !line.contains("rt", ignoreCase = true) && 
                 !line.contains("<v", ignoreCase = true))) {
                return@map line
            }

            try {
                var processedLine = line

                // 1. 화자 태그 고속 치환: <v Host>안녕하세요 -> [Host] 안녕하세요
                if (processedLine.contains("<v", ignoreCase = true)) {
                    vCount++
                    processedLine = processedLine.replace(Regex("(?i)<v\\s+([^>]+)>(.*?)(</v>)?"), "[$1] $2")
                }

                // 2. 다국어 태그 고속 치환: </?lang> 제거
                if (processedLine.contains("lang", ignoreCase = true)) {
                    processedLine = processedLine.replace(Regex("(?i)</?lang[^>]*>"), "")
                }

                // 3. 루비 태그 변환 (정규식 기반 4단계 빌드 - 원본 내장 중복 괄호 완전 처리)
                if (processedLine.contains("ruby", ignoreCase = true) || processedLine.contains("rt", ignoreCase = true)) {
                    // 3-1. 표준 규격 치환: <ruby>A<rt>B</rt></ruby> -> A(B)
                    val before1 = processedLine
                    processedLine = processedLine.replace(Regex("(?i)<ruby[^>]*>(.*?)<rt[^>]*>(.*?)</rt></ruby>")) { match ->
                        val base = match.groupValues[1]
                        val rt = match.groupValues[2].trim()
                            .removeSurrounding("(").removeSurrounding(")")
                            .removeSurrounding("（").removeSurrounding("）")
                        "$base($rt)"
                    }
                    if (processedLine != before1) rubyCount++

                    // 3-2. 변형 규격 치환: <ruby>A<rt>B</ruby> -> A(B)
                    val before2 = processedLine
                    processedLine = processedLine.replace(Regex("(?i)<ruby[^>]*>(.*?)<rt[^>]*>(.*?)</ruby>")) { match ->
                        val base = match.groupValues[1]
                        val rt = match.groupValues[2].trim()
                            .removeSurrounding("(").removeSurrounding(")")
                            .removeSurrounding("（").removeSurrounding("）")
                        "$base($rt)"
                    }
                    if (processedLine != before2) rubyCount++

                    // 3-3. 루비 없이 rt만 단독 사용된 경우: A<rt>B</rt> -> A(B)
                    val before3 = processedLine
                    processedLine = processedLine.replace(Regex("(?i)([^<\\s]+)<rt[^>]*>(.*?)</rt>")) { match ->
                        val base = match.groupValues[1]
                        val rt = match.groupValues[2].trim()
                            .removeSurrounding("(").removeSurrounding(")")
                            .removeSurrounding("（").removeSurrounding("）")
                        "$base($rt)"
                    }
                    if (processedLine != before3) rtCount++

                    // 3-4. 루비 없이 rt만 단독 사용되었으나 닫는 태그가 유실된 최악의 경우: 문<rt>게이트』 -> 문(게이트)』
                    val before4 = processedLine
                    processedLine = processedLine.replace(Regex("(?i)([^<\\s]+)<rt[^>]*>([^<』\\s]+)")) { match ->
                        val base = match.groupValues[1]
                        val rt = match.groupValues[2].trim()
                            .removeSurrounding("(").removeSurrounding(")")
                            .removeSurrounding("（").removeSurrounding("）")
                        "$base($rt)"
                    }
                    if (processedLine != before4) rtCount++

                    // 3-5. 혹시 남아있을 수 있는 잔여 쓰레기 태그 일괄 정리
                    processedLine = processedLine.replace(Regex("(?i)</?(ruby|rt|rp)[^>]*>"), "")
                }

                processedLine
            } catch (e: Exception) {
                line
            }
        }.joinToString("\n")

        println("[Linkkf-SubtitleProxy] [sanitizeWebVtt] 필터링 요약 -> 변환된 <ruby>: $rubyCount, 독립 <rt>: $rtCount, 화자 <v>: $vCount")
        return processedResult
    }
}
