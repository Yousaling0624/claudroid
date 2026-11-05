package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Loopback-socket coverage for [HttpUtil.postJson]: it is a transport helper, so the
 * only way to prove the verb/headers/status handling is to speak HTTP to it.
 */
class HttpUtilPostJsonTest {

    @Test
    fun `posts json with content type and returns the response body`() {
        val seen = AtomicReference("")
        withServer(status = "200 OK", body = """{"ok":true}""", seen = seen) { url ->
            val result = HttpUtil.postJson(
                url = url,
                body = """{"content":"hello"}""",
                headers = mapOf("x-api-key" to "k1"),
                direct = true,
            )
            assertEquals("""{"ok":true}""", result)
            val request = seen.get()
            assertTrue(request.startsWith("POST / "))
            assertTrue(request.contains("Content-Type: application/json"))
            assertTrue(request.contains("User-Agent: Claudroid/1.0 (Android)"))
            assertTrue(request.contains("x-api-key: k1"))
            assertTrue(request.contains("""{"content":"hello"}"""))
        }
    }

    @Test
    fun `non-2xx includes status and truncated error body but never an api key`() {
        withServer(
            status = "401 Unauthorized",
            body = """{"error":"bad key","echo":"Authorization: Bearer sk-secret-123"}""",
        ) { url ->
            try {
                HttpUtil.postJson("$url?key=sk-secret-123", "{}", emptyMap(), direct = true)
                fail("expected an exception")
            } catch (e: IllegalStateException) {
                val message = e.message.orEmpty()
                assertTrue(message, message.contains("401"))
                assertTrue(message, message.contains("bad key"))
                assertTrue(message, !message.contains("sk-secret-123"))
                assertTrue(message, !message.contains("key=sk"))
            }
        }
    }

    @Test
    fun `error body echoing an x-api-key header value is masked`() {
        withServer(
            status = "500 Internal Server Error",
            body = """{"error":"upstream failed","echo":"x-api-key: k2-secret-456"}""",
        ) { url ->
            try {
                HttpUtil.postJson(
                    url = url,
                    body = "{}",
                    headers = mapOf("x-api-key" to "k2-secret-456"),
                    direct = true,
                )
                fail("expected an exception")
            } catch (e: IllegalStateException) {
                val message = e.message.orEmpty()
                assertTrue(message, message.contains("500"))
                assertFalse(message, message.contains("k2-secret-456"))
            }
        }
    }

    /** Minimal single-shot HTTP server; the request head+body is captured in [seen]. */
    private fun withServer(
        status: String,
        body: String,
        seen: AtomicReference<String> = AtomicReference(""),
        block: (String) -> Unit,
    ) {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val worker = thread(isDaemon = true, name = "post-json-server") {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            runCatching {
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    var length = 0
                    val head = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        head.append(line).append('\n')
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    val chars = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val n = reader.read(chars, read, length - read)
                        if (n <= 0) break
                        read += n
                    }
                    seen.set(head.toString() + String(chars, 0, read))
                    val bytes = body.toByteArray()
                    val response = "HTTP/1.1 $status\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    s.getOutputStream().apply {
                        write(response.toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            block("http://127.0.0.1:$port/")
        } finally {
            runCatching { server.close() }
            worker.join(2_000)
        }
    }
}
