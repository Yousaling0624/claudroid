package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * The stream-json payloads below were captured from a real `claude` 2.1.318 binary
 * (see tools/mock-anthropic.mjs), so these tests pin the contract the UI depends on.
 */
class StreamParserTest {

    private fun parseAll(lines: List<String>): List<EngineEvent> {
        val parser = StreamParser()
        return lines.flatMap { parser.parse(it) }
    }

    @Test
    fun `init event carries session id and model`() {
        val events = parseAll(
            listOf(
                """{"type":"system","subtype":"init","cwd":"/work","session_id":"56135aa6-4f93-472a-a56d-12a15b37945b","tools":["Bash","Edit","Read"],"model":"mock-model","permissionMode":"bypassPermissions"}"""
            )
        )
        assertEquals(1, events.size)
        val init = events[0] as EngineEvent.SessionInit
        assertEquals("56135aa6-4f93-472a-a56d-12a15b37945b", init.sessionId)
        assertEquals("mock-model", init.model)
        assertEquals(listOf("Bash", "Edit", "Read"), init.tools)
    }

    @Test
    fun `partial message deltas produce text chunks`() {
        val events = parseAll(
            listOf(
                """{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_1"}},"session_id":"s1"}""",
                """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello from the mock endpoint. "}},"session_id":"s1"}""",
                """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Second sentence."}},"session_id":"s1"}""",
                // the full message arrives afterwards and must NOT be duplicated
                """{"type":"assistant","message":{"id":"msg_1","content":[{"type":"text","text":"Hello from the mock endpoint. Second sentence."}]},"session_id":"s1"}"""
            )
        )
        val text = events.filterIsInstance<EngineEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello from the mock endpoint. Second sentence.", text)
        assertEquals(2, events.filterIsInstance<EngineEvent.TextDelta>().size)
    }

    @Test
    fun `assistant text is used when the cli cannot stream deltas`() {
        val events = parseAll(
            listOf(
                """{"type":"assistant","message":{"id":"msg_1","content":[{"type":"text","text":"Fallback text"}]},"session_id":"s1"}"""
            )
        )
        val text = events.filterIsInstance<EngineEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Fallback text", text)
    }

    @Test
    fun `thinking deltas are separated from text`() {
        val events = parseAll(
            listOf(
                """{"type":"stream_event","event":{"type":"message_start","message":{"id":"m"}}}""",
                """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"let me check root"}}}""",
                """{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"done"}}}"""
            )
        )
        assertEquals("let me check root", events.filterIsInstance<EngineEvent.ThinkingDelta>().joinToString("") { it.text })
        assertEquals("done", events.filterIsInstance<EngineEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun `tool use and tool result are paired`() {
        val events = parseAll(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_01","name":"Bash","input":{"command":"magisk --install-module /sdcard/a.zip"}}]},"session_id":"s1"}""",
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01","content":"- Installing adblock\n- Done","is_error":false}]},"session_id":"s1"}"""
            )
        )
        val use = events.filterIsInstance<EngineEvent.ToolUse>().single()
        assertEquals("toolu_01", use.id)
        assertEquals("Bash", use.name)
        assertTrue(use.inputJson.contains("magisk --install-module"))

        val result = events.filterIsInstance<EngineEvent.ToolResult>().single()
        assertEquals("toolu_01", result.id)
        assertTrue(result.output.contains("- Done"))
        assertEquals(false, result.isError)
    }

    @Test
    fun `tool result given as content array is flattened`() {
        val events = parseAll(
            listOf(
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"line a"},{"type":"text","text":"line b"}]}]}}"""
            )
        )
        val result = events.filterIsInstance<EngineEvent.ToolResult>().single()
        assertEquals("line a\nline b", result.output)
    }

    @Test
    fun `result event exposes cost and diagnostics`() {
        val events = parseAll(
            listOf(
                """{"type":"result","subtype":"success","is_error":false,"duration_ms":106,"num_turns":2,"total_cost_usd":0.000688,"result":"final answer","session_id":"56135aa6"}"""
            )
        )
        val done = events.filterIsInstance<EngineEvent.TurnDone>().single()
        assertEquals("final answer", done.resultText)
        assertEquals(0.000688, done.costUsd!!, 1e-9)
        assertEquals(106L, done.durationMs)
        assertEquals(2, done.numTurns)
        assertEquals("success", done.subtype)
    }

    @Test
    fun `max turns error keeps its subtype`() {
        val events = parseAll(
            listOf(
                """{"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":10,"terminal_reason":"max_turns","session_id":"abc"}"""
            )
        )
        val done = events.filterIsInstance<EngineEvent.TurnDone>().single()
        assertTrue(done.isError)
        assertEquals("error_max_turns", done.subtype)
        assertEquals("", done.resultText)
    }

    @Test
    fun `non json output is surfaced as a log line`() {
        val events = parseAll(listOf("""[claude-code:unrecognized_model] {"model":"mock-model"}"""))
        assertTrue(events.single() is EngineEvent.Log)
    }
}

class TarGzTest {

    @Test
    fun `extracts the requested entry and ignores the rest`() {
        val archive = File.createTempFile("claude", ".tgz")
        val payload = ByteArray(5000) { (it % 251).toByte() }
        writeTarGz(
            archive,
            listOf(
                "package/package.json" to """{"name":"x"}""".toByteArray(),
                "package/claude" to payload,
                "package/README.md" to "hello".toByteArray(),
            )
        )
        val out = File.createTempFile("claude-bin", "")
        try {
            assertTrue(TarGz.extractEntry(archive, "package/claude", out))
            assertEquals(payload.size.toLong(), out.length())
            assertTrue(payload.contentEquals(out.readBytes()))
        } finally {
            archive.delete(); out.delete()
        }
    }

    @Test
    fun `missing entry reports false`() {
        val archive = File.createTempFile("musl", ".apk")
        writeTarGz(archive, listOf("lib/other.so" to ByteArray(10)))
        val out = File.createTempFile("entry", ".bin")
        try {
            assertEquals(false, TarGz.extractEntry(archive, "lib/ld-musl-aarch64.so.1", out))
        } finally {
            archive.delete(); out.delete()
        }
    }

    @Test
    fun `reads a text entry such as APKINDEX`() {
        val archive = File.createTempFile("index", ".tar.gz")
        val index = "C:Q1abc=\nP:musl\nV:1.2.5-r4\nA:aarch64\n\nP:busybox\nV:1.36.1-r31\n"
        writeTarGz(archive, listOf("APKINDEX" to index.toByteArray()))
        try {
            val text = TarGz.readTextEntry(archive, "APKINDEX")
            assertNotNull(text)
            assertTrue(text!!.contains("V:1.2.5-r4"))
            val block = text.split("\n\n").first { b -> b.lineSequence().any { it == "P:musl" } }
            assertEquals("1.2.5-r4", block.lineSequence().first { it.startsWith("V:") }.removePrefix("V:"))
        } finally {
            archive.delete()
        }
    }

    /** Minimal ustar writer, enough to exercise the reader. */
    private fun writeTarGz(out: File, entries: List<Pair<String, ByteArray>>) {
        GZIPOutputStream(FileOutputStream(out)).use { gz ->
            entries.forEach { (name, data) ->
                val header = ByteArray(512)
                put(header, 0, name)
                put(header, 100, "0000644")
                put(header, 108, "0000000")
                put(header, 116, "0000000")
                put(header, 124, java.lang.Long.toOctalString(data.size.toLong()).padStart(11, '0'))
                put(header, 136, "00000000000")
                header[156] = '0'.code.toByte()
                put(header, 257, "ustar")
                header[262] = 0
                put(header, 263, "00")
                for (i in 148 until 156) header[i] = ' '.code.toByte()
                var sum = 0
                header.forEach { sum += (it.toInt() and 0xFF) }
                put(header, 148, java.lang.Long.toOctalString(sum.toLong()).padStart(6, '0'))
                header[154] = 0
                header[155] = ' '.code.toByte()
                gz.write(header)
                gz.write(data)
                val pad = (512 - data.size % 512) % 512
                if (pad > 0) gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024))
        }
    }

    private fun put(buf: ByteArray, offset: Int, value: String) {
        val bytes = value.toByteArray()
        System.arraycopy(bytes, 0, buf, offset, minOf(bytes.size, buf.size - offset))
    }

    @Suppress("unused")
    private fun unusedFiles() = Files.createTempDirectory("noop")
}
