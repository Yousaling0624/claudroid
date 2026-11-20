package app.claudroid.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/** URL assembly and the raw-socket MKCOL from docs/BACKUP.md §5.1. */
class WebDavTargetTest {

    @Test
    fun `parent collections are listed shallowest first and exclude the file`() {
        assertEquals(
            listOf(
                "https://dav.example.com/remote.php",
                "https://dav.example.com/remote.php/dav",
                "https://dav.example.com/remote.php/dav/files",
                "https://dav.example.com/remote.php/dav/files/me",
            ),
            WebDavTarget.parentCollections(
                "https://dav.example.com/remote.php/dav/files/me/claudroid-backup.zip",
            ),
        )
    }

    @Test
    fun `parent collections handle roots, ports, escapes and bad input`() {
        assertEquals(emptyList<String>(), WebDavTarget.parentCollections("https://h/backup.zip"))
        assertEquals(emptyList<String>(), WebDavTarget.parentCollections("not a url"))
        assertEquals(
            listOf("http://127.0.0.1:8080/dav"),
            WebDavTarget.parentCollections("http://127.0.0.1:8080/dav/backup.zip"),
        )
        // The raw path is preserved: re-encoding an escaped name would break MKCOL.
        assertEquals(
            listOf("https://h/a%20b"),
            WebDavTarget.parentCollections("https://h/a%20b/backup.zip?token=1"),
        )
    }

    @Test
    fun `auth header is only sent when a username is configured`() {
        assertNull(WebDavTarget.authHeader(WebDavConfig(url = "https://h/f.zip")))
        val expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".toByteArray())
        assertEquals(expected, WebDavTarget.authHeader(WebDavConfig(username = "user", password = "pass")))
    }

    @Test
    fun `label uses the host and never the credential fields`() {
        assertEquals(
            "WebDAV · dav.example.com",
            WebDavTarget.label(WebDavConfig(url = "https://dav.example.com/a/b.zip", username = "u", password = "p")),
        )
    }

    @Test(timeout = 10_000)
    fun `raw socket MKCOL sends the custom verb and Authorization`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val captured = AtomicReference<String>()
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    val text = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        text.append(line).append('\n')
                        if (line.isEmpty()) break
                    }
                    captured.set(text.toString())
                    val out = socket.getOutputStream()
                    out.write(
                        "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(StandardCharsets.ISO_8859_1),
                    )
                    out.flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            val code = WebDavTarget.mkcol(
                url = "http://127.0.0.1:$port/dav/files/me/backup.zip",
                authorization = "Basic ${Base64.getEncoder().encodeToString("user:pass".toByteArray())}",
                timeoutMs = 5_000,
            )
            assertEquals("MKCOL must be sent as a raw HTTP request, not via HttpURLConnection", 201, code)
        } finally {
            server.close()
            thread.join(5_000)
        }
        val request = captured.get().orEmpty()
        assertTrue(request, request.startsWith("MKCOL /dav/files/me/backup.zip HTTP/1.1"))
        assertTrue(request, request.contains("Host: 127.0.0.1:$port"))
        assertTrue(request, request.contains("Authorization: Basic "))
        assertTrue(request, request.contains("Content-Length: 0"))
    }
}
