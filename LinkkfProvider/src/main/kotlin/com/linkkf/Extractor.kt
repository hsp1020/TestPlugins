package com.linkkf

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpExchange
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.jsoup.Jsoup

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
 * [프로덕션 등급] 자막 처리를 위한 자율 제어식 백그라운드 로컬 루프백 프록시 서버
 */
object SubtitleProxy {
    private var server: HttpServer? = null
    private var executor: ScheduledExecutorService? = null
    private var port: Int = 0
    private var shutdownTask: ScheduledFuture<*>? = null
    private val lock = Any()

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
     * 동기화 블록 내에서 소켓 서버와 데몬 스레드 풀을 안전하게 초기화합니다.
     */
    private fun ensureServerRunning() {
        if (server == null) {
            try {
                val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                
                // 안드로이드 VM 종료를 방해하지 않는 '데몬 스레드' 기반의 싱글 워커 스레드 풀 생성
                val exec = Executors.newScheduledThreadPool(1) { runnable ->
                    Thread(runnable, "Linkkf-SubtitleProxy-Worker").apply {
                        isDaemon = true // 데몬 스레드로 지정하여 리소스 누수 및 앱 강제 종료 지연 방지
                    }
                }
                
                s.createContext("/sub", SubtitleHandler())
                s.executor = exec
                s.start()

                server = s
                executor = exec
                port = s.address.port
            } catch (e: Exception) {
                // 예외 처리 폴백
            }
        }
    }

    /**
     * 무활동 감지 타이머를 리셋합니다. 자막 전송이나 가공이 이루어지면 타이머가 뒤로 미뤄집니다.
     */
    private fun resetShutdownTimer() {
        shutdownTask?.cancel(false)
        shutdownTask = executor?.schedule({
            synchronized(lock) {
                stopServer()
            }
        }, IDLE_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    /**
     * 소켓을 닫고, 스레드 풀을 완전 파괴하며, 정적 메모리 참조를 해제하여 가비지 컬렉터(GC)가 모든 메모리를 회수하게 유도합니다.
     */
    private fun stopServer() {
        try {
            server?.stop(0)
        } catch (e: Exception) {}
        try {
            executor?.shutdownNow()
        } catch (e: Exception) {}
        
        server = null
        executor = null
        port = 0
        shutdownTask = null
    }

    /**
     * 플레이어 및 다운로더의 실제 HTTP 호출을 전담하여 가공 전송하는 핸들러
     */
    private class SubtitleHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            synchronized(lock) {
                resetShutdownTimer() // 요청이 인입되었으므로 유휴 종료 타이머 즉시 연장
            }

            try {
                val query = exchange.requestURI.query ?: ""
                var originalUrl = ""

                // 쿼리 파라미터 파싱 (?url=...)
                val pairs = query.split("&")
                for (pair in pairs) {
                    val idx = pair.indexOf("=")
                    if (idx > 0 && URLDecoder.decode(pair.substring(0, idx), "UTF-8") == "url") {
                        originalUrl = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        break
                    }
                }

                if (originalUrl.isEmpty()) {
                    exchange.sendResponseHeaders(400, 0)
                    exchange.close()
                    return
                }

                // 워커 스레드 풀 내부에서 원격 VTT 자막을 동기식으로 호출
                val rawVtt = kotlinx.coroutines.runBlocking {
                    app.get(originalUrl).text
                }

                val sanitizedVtt = sanitizeWebVtt(rawVtt, originalUrl)
                val responseBytes = sanitizedVtt.toByteArray(StandardCharsets.UTF_8)

                // CORS 허용 및 올바른 VTT 자막 헤더를 전송하여 재생기와 다운로더가 온전히 처리할 수 있게 보장
                exchange.responseHeaders.set("Content-Type", "text/vtt; charset=utf-8")
                exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
                
                exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                
                val os = exchange.responseBody
                os.write(responseBytes)
                os.close()
            } catch (e: Exception) {
                try {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.close()
                } catch (ex: Exception) {}
            }
        }
    }

    /**
     * 극도의 연산 압축 가공 엔진 (무활동 시 Jsoup 빌드조차 거치지 않음)
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
                    
                    doc.select("ruby").forEach { ruby ->
                        val baseText = ruby.ownText()
                        val rtText = ruby.select("rt").text()
                        if (baseText.isNotEmpty() && rtText.isNotEmpty()) {
                            ruby.replaceWith(org.jsoup.nodes.TextNode("$baseText($rtText)"))
                        } else if (rtText.isNotEmpty()) {
                            ruby.replaceWith(org.jsoup.nodes.TextNode(rtText))
                        }
                    }

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
