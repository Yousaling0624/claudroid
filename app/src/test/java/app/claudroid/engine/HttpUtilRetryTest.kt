package app.claudroid.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Reproduces the exact failure seen on a real device: the first transfer dies halfway
 * through, and the next attempt must continue from where it stopped instead of
 * reporting success with a truncated file.
 */
class HttpUtilRetryTest {

    @Test
    fun `resumes a truncated transfer instead of accepting a short file`() {
        val payload = ByteArray(3_000_000) { (it % 251).toByte() }
        val requests = AtomicInteger(0)

        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        thread(isDaemon = true, name = "mock-http") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        var rangeStart = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeStart = line.substringAfter("bytes=", "")
                                    .substringBefore("-").toIntOrNull() ?: 0
                            }
                        }
                        val attempt = requests.incrementAndGet()
                        val first = attempt == 1
                        val body = if (first) payload.copyOfRange(0, payload.size / 2)
                        else payload.copyOfRange(rangeStart, payload.size)
                        val status = if (first || rangeStart == 0) "200 OK" else "206 Partial Content"
                        val head = buildString {
                            append("HTTP/1.1 $status\r\n")
                            // On the first attempt we promise the full size but send half,
                            // then close: a faithful copy of what a dying connection looks like.
                            append("Content-Length: ${payload.size - if (first) 0 else rangeStart}\r\n")
                            if (!first && rangeStart > 0) {
                                append("Content-Range: bytes $rangeStart-${payload.size - 1}/${payload.size}\r\n")
                            }
                            append("Accept-Ranges: bytes\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        val out = s.getOutputStream()
                        out.write(head.toByteArray())
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }

        val dest = File.createTempFile("resume", ".bin")
        try {
            val digest = HttpUtil.download("http://127.0.0.1:$port/claude.tgz", dest)
            assertTrue("expected a retry, saw ${requests.get()} request(s)", requests.get() >= 2)
            assertArrayEquals(payload, dest.readBytes())
            assertEquals(HttpUtil.sha512Base64(dest), digest)
        } finally {
            runCatching { server.close() }
            dest.delete()
        }
    }
}
