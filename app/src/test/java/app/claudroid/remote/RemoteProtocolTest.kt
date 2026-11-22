package app.claudroid.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteProtocolTest {

    @Test
    fun `decodes a cli event from the daemon`() {
        val event = RemoteJson.json.decodeFromString(
            RemoteEvent.serializer(),
            """{"kind":"cli","seq":7,"turnId":"t-1","owner":"device","line":"{\"type\":\"result\"}"}""",
        )
        val cli = event as RemoteEvent.Cli
        assertEquals(7L, cli.seq)
        assertEquals("t-1", cli.turnId)
        assertTrue(cli.line.contains("\"result\""))
    }

    @Test
    fun `decodes a permission event with tool input`() {
        val event = RemoteJson.json.decodeFromString(
            RemoteEvent.serializer(),
            """{"kind":"permission","seq":8,"turnId":"t-1","owner":"device",
               "requestId":"req-1","toolUseID":"tool-1","toolName":"Bash",
               "input":{"command":"ls"},"suggestions":[{"type":"allow"}],
               "title":"运行命令","expiresAt":1700000000000}""",
        )
        val permission = event as RemoteEvent.Permission
        assertEquals("req-1", permission.requestId)
        assertEquals("Bash", permission.toolName)
        assertEquals("运行命令", permission.title)
        assertNotNull(permission.input)
        assertTrue(permission.input.toString().contains("ls"))
        assertTrue(permission.suggestions.toString().contains("allow"))
    }

    @Test
    fun `decodes turn and tunnel events`() {
        val turn = RemoteJson.json.decodeFromString(
            RemoteEvent.serializer(),
            """{"kind":"turn","seq":9,"turnId":"t-1","owner":"d","phase":"done","exitCode":0}""",
        ) as RemoteEvent.Turn
        assertEquals("done", turn.phase)
        assertEquals(0, turn.exitCode)

        val tunnel = RemoteJson.json.decodeFromString(
            RemoteEvent.serializer(),
            """{"kind":"tunnel","seq":10,"tunnel":{"mode":"quick","state":"up",
               "url":"https://x.trycloudflare.com","changedAt":1}}""",
        ) as RemoteEvent.Tunnel
        assertEquals("up", tunnel.tunnel.state)
        assertEquals("https://x.trycloudflare.com", tunnel.tunnel.url)
    }

    @Test
    fun `normalises host addresses`() {
        assertEquals("http://host:8787", RemoteClient.normalizeBaseUrl("host:8787"))
        assertEquals("https://example.com/prefix", RemoteClient.normalizeBaseUrl("https://example.com/prefix/"))
        assertEquals("http://127.0.0.1:8787", RemoteClient.normalizeBaseUrl(" http://127.0.0.1:8787/ "))
    }

    @Test
    fun `rejects unsafe or ambiguous host addresses`() {
        listOf("", "ftp://example.com", "https://example.com?x=1", "https://example.com#f").forEach { raw ->
            try {
                RemoteClient.normalizeBaseUrl(raw)
                fail("expected rejection for $raw")
            } catch (_: RemoteException) {
                // expected
            }
        }
    }

    @Test
    fun `friendly errors never echo a token`() {
        val message = RemoteClient.friendlyNetworkError(RemoteException("配对已失效", 401))
        assertEquals("配对已失效", message)
    }
}
