package app.claudroid.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun `parses a frame split across chunks`() {
        val parser = SseParser()
        assertTrue(parser.feed("id: 42\nevent: cli\nda").isEmpty())
        val frames = parser.feed("ta: {\"a\":1}\n\n")
        assertEquals(1, frames.size)
        assertEquals("cli", frames[0].event)
        assertEquals(42L, frames[0].id)
        assertEquals("{\"a\":1}", frames[0].data)
    }

    @Test
    fun `joins multiline data and accepts crlf`() {
        val frames = SseParser().feed("event: cli\r\ndata: first\r\ndata: second\r\n\r\n")
        assertEquals(1, frames.size)
        assertEquals("first\nsecond", frames[0].data)
    }

    @Test
    fun `ignores retry fields and heartbeats`() {
        val frames = SseParser().feed("retry: 3000\n: ping\n\n")
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `returns every complete frame from one chunk`() {
        val frames = SseParser().feed(
            "id: 1\nevent: cli\ndata: a\n\n" +
                "id: 2\nevent: turn\ndata: b\n\n" +
                "id: 3\nevent: cli\ndata: partial",
        )
        assertEquals(2, frames.size)
        assertEquals(listOf("a", "b"), frames.map { it.data })
        assertEquals(listOf(1L, 2L), frames.map { it.id })
    }

    @Test
    fun `defaults the event name to message`() {
        val frames = SseParser().feed("data: hello\n\n")
        assertEquals("message", frames.single().event)
    }
}
