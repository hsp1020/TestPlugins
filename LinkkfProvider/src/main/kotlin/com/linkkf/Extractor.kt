package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import org.jsoup.Jsoup
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
                SubtitleProxy.getProxyUrl(rawSubUrl)
            } else {
                ""
            }

            if (actualUrl.contains(".m3u8")) return LinkkfResult(actualUrl, finalSubtitleUrl, needsWebView = false)
            return LinkkfResult(actualUrl, finalSubtitleUrl, needsWebView = true)
        } catch (e: Exception) { return null }
    }

    private data class PlayerData(
        @JsonProperty("url") val url: String?,
        @JsonProperty("actual_url") val actualUrl: String?
    )
}

/**
 * [표준 순수 소켓 버전] 외부 라이브러리 및 com.sun 의존성이 없는 완전 순수 자막 프록시 서버
 */
object SubtitleProxy {
    private var serverSocket: ServerSocket? = null
    private var executor: ScheduledExecutorService? = null
    private var port: Int = 0
    private var shutdownTask: ScheduledFuture<*>? = null
    private val lock = Any()
    private var isRunning = false

    // 2분(120초) 동안 아무런 자막 요청이 없으면 서버와 스레드 풀을 완전히 파괴하여 메모리를 회수합니다.
    private const val IDLE_TIMEOUT_SEC = 120L

    /**
     * 원본 VTT 자막 주소를 로컬 프록시 주소로 변환하며, 서버가 꺼져 있을 시 안전하게 재기동합니다.
     */
    fun getProxyUrl(originalUrl: String): String {
        synchronized(lock) {
            ensureServerRunning()
            resetShutdownTimer()
        }
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
        return "http://127.0.0.1:$port/sub?url=$encodedUrl"
    }

    /**
     * 동기화 블록 내에서 순수 자바 ServerSocket과 데몬 스레드 풀을 안전하게 기동합니다.
     */
    private fun ensureServerRunning() {
        if (serverSocket == null || !isRunning) {
            try {
                // OS가 제공하는 안전한 무작위 포트 점유 (127.0.0.1 로컬 루프백에만 제한 바인딩)
                val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                port = ss.localPort
                isRunning = true

                // 데몬 스레드 풀 생성
                val exec = Executors.newScheduledThreadPool(1) { runnable ->
                    Thread(runnable, "Linkkf-SubtitleProxy-Worker").apply {
                        isDaemon = true
                    }
                }
                executor = exec

                // 소켓 수신 대기 루프 백그라운드 구동
                exec.execute {
                    while (isRunning) {
                        try {
                            val clientSocket = ss.accept()
                            exec.execute {
                                handleClient(clientSocket)
                            }
                        } catch (e: Exception) {
                            // 소켓이 외부에서 강제 Close되면 예외와 함께 루프가 안전하게 종료됩니다.
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // 예외 처리 폴백
            }
        }
    }

    /**
     * 유입된 소켓 HTTP 연결을 비동기로 안전하게 받아 요청을 분석하고 가공 자막을 전송합니다.
     */
    private fun handleClient(socket: Socket) {
        synchronized(lock) {
            resetShutdownTimer() // 요청 인입 시 소멸 시간 재설정
        }

        var reader: BufferedReader? = null
        var output: OutputStream? = null
        try {
            socket.soTimeout = 5000 // 소켓 고정 타임아웃 5초 설정 (네트워크 불완전 락 방지)
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            output = socket.getOutputStream()

            val firstLine = reader.readLine() ?: return
            
            // HTTP Request 라인 검증 (예: GET /sub?url=... HTTP/1.1)
            val parts = firstLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendErrorResponse(output, 400, "Bad Request")
                return
            }

            val pathWithQuery = parts[1]
            if (!pathWithQuery.startsWith("/sub")) {
                sendErrorResponse(output, 404, "Not Found")
                return
            }

            val queryIdx = pathWithQuery.indexOf("?")
            if (queryIdx == -1) {
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
                sendErrorResponse(output, 400, "Empty URL")
                return
            }

            // 원격 원본 VTT를 백그라운드 스레드에서 안전히 동기 다운로드
            val rawVtt = kotlinx.coroutines.runBlocking {
                app.get(originalUrl).text
            }

            val sanitizedVtt = sanitizeWebVtt(rawVtt, originalUrl)
            val responseBytes = sanitizedVtt.toByteArray(StandardCharsets.UTF_8)

            // HTTP/1.1 규격에 맞춘 성공 헤더와 바이트 버퍼 직접 전송 (CORS 완전 지원)
            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 200 OK\r\n")
            headerBuilder.append("Content-Type: text/vtt; charset=utf-8\r\n")
            headerBuilder.append("Content-Length: ${responseBytes.size}\r\n")
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
            headerBuilder.append("Connection: close\r\n")
            headerBuilder.append("\r\n")

            output.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
            output.write(responseBytes)
            output.flush()
        } catch (e: Exception) {
            try {
                output?.let { sendErrorResponse(it, 500, "Internal Server Error") }
            } catch (ex: Exception) {}
        } finally {
            try { reader?.close() } catch (e: Exception) {}
            try { output?.close() } catch (e: Exception) {}
            try { socket.close() } catch (e: Exception) {}
        }
    }

    /**
     * 예외 상황 발생 시 표준 HTTP 본문 형태로 헤더와 데이터를 송출합니다.
     */
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
        } catch (e: Exception) {}
    }

    private fun resetShutdownTimer() {
        shutdownTask?.cancel(false)
        shutdownTask = executor?.schedule({
            synchronized(lock) {
                stopServer()
            }
        }, IDLE_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private fun stopServer() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        try {
            executor?.shutdownNow()
        } catch (e: Exception) {}
        
        serverSocket = null
        executor = null
        port = 0
        shutdownTask = null
    }

    /**
     * 초고속 저전력 VTT 자막 가공 및 정밀 필터링 엔진 (무활동 시 Jsoup 빌드 생략)
     */
    private fun sanitizeWebVtt(rawVtt: String, fallbackUrl: String): String {
        if (!rawVtt.contains("ruby", ignoreCase = true) && 
            !rawVtt.contains("rt", ignoreCase = true) && 
            !rawVtt.contains("<v", ignoreCase = true)) {
            return rawVtt
        }

        return rawVtt.lineSequence().map { line ->
            if (!line.contains("<") || 
                (!line.contains("ruby", ignoreCase = true) && 
                 !line.contains("rt", ignoreCase = true) && 
                 !line.contains("<v", ignoreCase = true))) {
                return@map line
            }

            try {
                var processedLine = line

                // 1. 화자 태그 고속 치환
                if (processedLine.contains("<v", ignoreCase = true)) {
                    processedLine = processedLine.replace(Regex("<v\\s+([^>]+)>(.*?)(</v>)?"), "[$1] $2")
                }

                // 2. 다국어 태그 고속 치환
                if (processedLine.contains("lang", ignoreCase = true)) {
                    processedLine = processedLine.replace(Regex("</?lang[^>]*>"), "")
                }

                // 3. 루비 및 rt 문법 보정
                if (processedLine.contains("ruby", ignoreCase = true) || processedLine.contains("rt", ignoreCase = true)) {
                    val doc = Jsoup.parseBodyFragment(processedLine)
                    
                    // 표준 루비 태그 변환
                    doc.select("ruby").forEach { ruby ->
                        val baseText = ruby.ownText()
                        val rtText = ruby.select("rt").text()
                        if (baseText.isNotEmpty() && rtText.isNotEmpty()) {
                            ruby.replaceWith(org.jsoup.nodes.TextNode("$baseText($rtText)"))
                        } else if (rtText.isNotEmpty()) {
                            ruby.replaceWith(org.jsoup.nodes.TextNode(rtText))
                        }
                    }

                    // 독립형 rt 태그 보정
                    doc.select("rt").forEach { rt ->
                        if (rt.parent()?.normalName() != "ruby") {
                            val rtText = rt.text()
                            if (rtText.isNotEmpty()) {
                                rt.replaceWith(org.jsoup.nodes.TextNode("($rtText)"))
                            } else {
                                rt.remove()
                            }
                        }
                    }

                    processedLine = doc.body().html()
                }

                processedLine
            } catch (e: Exception) {
                line
            }
        }.joinToString("\n")
    }
}
