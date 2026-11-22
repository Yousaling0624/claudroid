package app.claudroid.remote

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Exercises the real HTTP + JSON path against a loopback server. The daemon's actual
 * payloads are used verbatim because the previous bug (decoding the body twice) only
 * shows up when the response is a JSON object, not a JSON string.
 */
class RemoteClientHttpTest {

    @Test
    fun `lists a host directory with fractional mtime values`() {
        val listing = """
            {"entries":[
              {"name":".android","path":"/home/me/.android","isDir":true,"size":4096,"mtimeMs":1793882701461.8994},
              {"name":"note.txt","path":"/home/me/note.txt","isDir":false,"size":12,"mtimeMs":1793882701461}
            ]}
        """.trimIndent()
        withServer(mapOf("/v1/fs?op=list&path=%2Fhome%2Fme" to listing)) { client ->
            val entries = client.listFiles("/home/me")
            assertEquals(listOf(".android", "note.txt"), entries.map { it.name })
            assertTrue(entries[0].isDir)
            assertFalse(entries[1].isDir)
            assertEquals(12L, entries[1].size)
            assertEquals(1793882701461.8994, entries[0].mtimeMs, 0.0001)
        }
    }

    @Test
    fun `stats one host file`() {
        val body = """
            {"name":"note.txt","path":"/home/me/note.txt","isDir":false,"size":12,"mtimeMs":1793882701461.5}
        """.trimIndent()
        withServer(mapOf("/v1/fs?op=stat&path=%2Fhome%2Fme%2Fnote.txt" to body)) { client ->
            val entry = client.stat("/home/me/note.txt")
            assertEquals("note.txt", entry.name)
            assertEquals(12L, entry.size)
        }
    }

    @Test
    fun `decodes a poll batch and the eventPoll capability`() {
        val batch = """
            {"events":[
              {"kind":"turn","seq":41,"turnId":"t-1","owner":"device","phase":"started"},
              {"kind":"cli","seq":42,"turnId":"t-1","owner":"device","line":"{\"type\":\"result\"}"}
            ],"latestSeq":42}
        """.trimIndent()
        val health = """
            {"protocol":1,"hostName":"me","eventPoll":true,"workspaceRoots":["/home/me"],
             "permissionModes":["default"],"tunnel":{"mode":"off","state":"off","changedAt":0}}
        """.trimIndent()
        withServer(
            mapOf(
                "/v1/events/batch?since=40&timeout=15000" to batch,
                "/v1/health" to health,
            ),
        ) { client ->
            val result = client.eventsSince(40)
            assertEquals(42L, result.latestSeq)
            assertEquals(2, result.events.size)
            assertTrue((result.events[1] as RemoteEvent.Cli).line.contains("result"))
            assertTrue(client.health().eventPoll)
        }
    }

    private fun <T> withServer(routes: Map<String, String>, block: (RemoteClient) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val key = exchange.requestURI.toString()
            val payload = routes[key]
            val bytes = (payload ?: """{"error":"unexpected $key"}""").toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(if (payload == null) 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return try {
            block(RemoteClient("http://127.0.0.1:${server.address.port}", "test-token"))
        } finally {
            server.stop(0)
        }
    }
}
