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
 * [표준 순수 소켓 버전 - 구조적 토큰 파서 탑재] com.sun 의존성 없이 멀티스레드 소켓 루프 및 상태 기계로 구동되는 안정 버전
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
            println("[Linkkf-SubtitleProxy] [handleClient] 실시간 구조적 자막 가공(Token-based Parser) 엔진을 구동합니다...")
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

    // 어휘 파싱을 위한 토큰 객체
    private class VttToken(val isTag: Boolean, val content: String)

    /**
     * 문맥과 구조를 완벽하게 인지하는 컴파일러 기법의 자막 파서 엔진
     */
    private fun sanitizeWebVtt(rawVtt: String, fallbackUrl: String): String {
        // 루비, 화자, 이스케이프 패턴이 아예 없는 평범한 자막은 그대로 패스 (초고속 성능 보장)
        if (!rawVtt.contains("ruby", ignoreCase = true) && 
            !rawVtt.contains("rt", ignoreCase = true) && 
            !rawVtt.contains("<v", ignoreCase = true) && 
            !rawVtt.contains("&lt;", ignoreCase = true)) {
            println("[Linkkf-SubtitleProxy] [sanitizeWebVtt] 원본에 루비/화자/인코딩 패턴이 전혀 없음. 무가공 초고속 패스.")
            return rawVtt
        }

        var rubyCount = 0
        var vCount = 0

        val processedResult = rawVtt.lineSequence().map { line ->
            // 가공이 필요 없는 대사는 일절 건드리지 않고 그대로 보존
            if (!line.contains("<") && !line.contains("&lt;")) {
                return@map line
            }

            try {
                // 1. 해당 라인을 표준 태그 영역과 일반 텍스트 영역으로 완전하게 분리 (토큰 어휘 분석)
                val tokens = tokenizeVttLine(line)
                
                // 2. 상태 머신 구동하여 태그 조립 및 가공 시작
                val resultBuilder = StringBuilder()
                var insideRt = false
                val rtContent = StringBuilder()

                for (token in tokens) {
                    if (token.isTag) {
                        val tagLower = token.content.lowercase()
                        
                        // 루비, rt 태그의 진입 및 탈출 분기점 분석
                        val isRubyStart = tagLower.startsWith("<ruby") || tagLower.startsWith("&lt;ruby")
                        val isRubyEnd = tagLower == "</ruby>" || tagLower == "&lt;/ruby&gt;"
                        val isRtStart = tagLower.startsWith("<rt") || tagLower.startsWith("&lt;rt")
                        val isRtEnd = tagLower == "</rt>" || tagLower == "&lt;/rt&gt;"

                        // 화자(Voice) 태그 처리 분기점
                        val isVoiceStart = tagLower.startsWith("<v ") || tagLower.startsWith("&lt;v ")
                        val isVoiceEnd = tagLower == "</v>" || tagLower == "&lt;/v&gt;"

                        when {
                            isRubyStart || isRubyEnd -> {
                                // 루비 마크업 태그 자체는 출력 텍스트에서 완전히 제거합니다.
                                if (isRubyStart) rubyCount++
                            }
                            isRtStart -> {
                                insideRt = true
                                rtContent.setLength(0) // 버퍼 클리어
                            }
                            isRtEnd -> {
                                insideRt = false
                                val annotation = rtContent.toString().trim()
                                    .removeSurrounding("(").removeSurrounding(")")
                                    .removeSurrounding("（").removeSurrounding("）")
                                if (annotation.isNotEmpty()) {
                                    resultBuilder.append("($annotation)")
                                }
                            }
                            isVoiceStart -> {
                                vCount++
                                // <v Host> 에서 'Host' 만 추출하여 [Host] 형태로 전방에 강제 주입
                                val speaker = if (tagLower.startsWith("<v")) {
                                    token.content.substring(2, token.content.length - 1).trim()
                                } else {
                                    token.content.substring(5, token.content.length - 4).trim()
                                }
                                if (speaker.isNotEmpty()) {
                                    resultBuilder.append("[$speaker] ")
                                }
                            }
                            isVoiceEnd -> {
                                // 화자 닫는 태그는 단순 폐기
                            }
                            else -> {
                                // b, i, u, c 등 ExoPlayer 가 자체 지원하는 표준 서체 효과 태그는
                                // 가공하지 않고 원본 토큰 그대로 흘려보내 시각 효과를 온전히 유지시킵니다.
                                if (!insideRt) {
                                    resultBuilder.append(token.content)
                                }
                            }
                        }
                    } else {
                        // 텍스트 토큰인 경우
                        if (insideRt) {
                            rtContent.append(token.content) // rt 영역 내부라면 괄호 처리 버퍼에 적재
                        } else {
                            resultBuilder.append(token.content) // 일반 텍스트면 최종 출력 스트림에 적재
                        }
                    }
                }

                // 혹시나 닫는 rt 태그가 소실된 비표준 자막 행의 경우에 대비한 자동 회수 예외 처리
                if (insideRt && rtContent.isNotEmpty()) {
                    val annotation = rtContent.toString().trim()
                        .removeSurrounding("(").removeSurrounding(")")
                        .removeSurrounding("（").removeSurrounding("）")
                    if (annotation.isNotEmpty()) {
                        resultBuilder.append("($annotation)")
                    }
                }

                resultBuilder.toString()
            } catch (e: Exception) {
                line // 혹시 모를 내부 구조 변환 중 에러 발생 시 원본 라인 안전 보존
            }
        }.joinToString("\n")

        println("[Linkkf-SubtitleProxy] [sanitizeWebVtt] 구조 분석 요약 -> 변환된 <ruby>: $rubyCount, 화자 <v>: $vCount")
        return processedResult
    }

    /**
     * VTT 한 줄의 모든 문자열을 순방향 $O(N)$으로 훑으며 태그와 일반 문자로 분해하는 고성능 토크나이저
     */
    private fun tokenizeVttLine(line: String): List<VttToken> {
        val tokens = mutableListOf<VttToken>()
        var i = 0
        val n = line.length
        val sb = StringBuilder()

        while (i < n) {
            // 표준 꺾쇠 < 형태의 태그 추출
            if (line.startsWith("<", i)) {
                if (sb.isNotEmpty()) {
                    tokens.add(VttToken(false, sb.toString()))
                    sb.setLength(0)
                }
                val endIdx = line.indexOf(">", i)
                if (endIdx != -1) {
                    tokens.add(VttToken(true, line.substring(i, endIdx + 1)))
                    i = endIdx + 1
                } else {
                    sb.append(line[i])
                    i++
                }
            } 
            // 이스케이프 &lt; 형태의 태그 추출
            else if (line.startsWith("&lt;", i)) {
                if (sb.isNotEmpty()) {
                    tokens.add(VttToken(false, sb.toString()))
                    sb.setLength(0)
                }
                val endIdx = line.indexOf("&gt;", i)
                if (endIdx != -1) {
                    tokens.add(VttToken(true, line.substring(i, endIdx + 4)))
                    i = endIdx + 4
                } else {
                    sb.append(line[i])
                    i++
                }
            } 
            // 일반 텍스트 누적
            else {
                sb.append(line[i])
                i++
            }
        }
        
        if (sb.isNotEmpty()) {
            tokens.add(VttToken(false, sb.toString()))
        }
        return tokens
    }
}
