package app.claudroid.engine.openai

import app.claudroid.data.ApiFlavor
import app.claudroid.data.AppSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * End-to-end: a fake OpenAI-compatible upstream on a loopback socket, the real
 * [OpenAiProxy] in front of it, and an Anthropic-shaped request coming in.
 * This is the wiring that either works on a phone or doesn't — better to find out here.
 */
class OpenAiProxyTest {

    @Test
    fun `anthropic request is translated to openai and streamed back as anthropic events`() {
        val requests = AtomicInteger(0)
        val upstream = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        val upstreamPort = upstream.localPort

        thread(isDaemon = true, name = "fake-openai") {
            while (!upstream.isClosed) {
                val socket = runCatching { upstream.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        val body = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(body, read, contentLength - read)
                            if (n <= 0) break
                            read += n
                        }
                        requests.incrementAndGet()
                        // Echo a canned streaming answer; the proxy must translate it.
                        val frames = listOf(
                            """data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}""",
                            """data: {"choices":[{"index":0,"delta":{"content":"lo"}}]}""",
                            """data: {"choices":[{"index":0,"finish_reason":"stop"}]}""",
                            "data: [DONE]",
                        ).joinToString("\n\n") + "\n\n"
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Connection: close\r\n\r\n"
                        s.getOutputStream().apply {
                            write(head.toByteArray())
                            write(frames.toByteArray())
                            flush()
                        }
                    }
                }
            }
        }

        val settings = AppSettings(
            apiFlavor = ApiFlavor.OPENAI,
            baseUrl = "http://127.0.0.1:$upstreamPort",
            apiKey = "sk-test",
            model = "group/auto-deepseek-flash",
        )
        val proxy = OpenAiProxy(settings)
        proxy.start()
        try {
            val conn = (URL("${proxy.baseUrl}/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
            val request = """
                {"model":"claude-sonnet-4-5","max_tokens":256,"stream":true,
                 "messages":[{"role":"user","content":[{"type":"text","text":"hi"}]}]}
            """.trimIndent()
            conn.outputStream.use { it.write(request.toByteArray()) }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            assertTrue("upstream was never called: $response", requests.get() >= 1)
            assertTrue("missing message_start: $response", response.contains("event: message_start"))
            assertTrue("missing text delta: $response", response.contains("\"text\":\"Hel\""))
            assertTrue("missing second delta: $response", response.contains("\"text\":\"lo\""))
            assertTrue("missing message_stop: $response", response.contains("event: message_stop"))
            assertTrue("missing stop reason", response.contains("\"stop_reason\":\"end_turn\""))
        } finally {
            runCatching { proxy.stop() }
            runCatching { upstream.close() }
        }
    }
}
