package app.claudroid.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TurnOutputPumpTest {

    @Test
    fun `emits lines and returns at eof`() {
        val lines = mutableListOf<String>()
        TurnOutputPump(
            stream = "line1\nline2\n".byteInputStream(),
            isCancelled = { false },
            isProcessAlive = { true },
            onLine = { lines += it },
            pollMs = 10,
        ).run()
        assertEquals(listOf("line1", "line2"), lines)
    }

    @Test
    fun `returns on cancellation even when the stream never closes`() {
        val cancelled = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        Thread {
            TurnOutputPump(
                stream = NeverEndingStream(),
                isCancelled = { cancelled.get() },
                isProcessAlive = { true },
                onLine = {},
                pollMs = 10,
            ).run()
            finished.countDown()
        }.start()
        Thread.sleep(100)
        cancelled.set(true)
        assertTrue(
            "pump must not wait for EOF after a stop",
            finished.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `stops after the process exits even if a child keeps the pipe open`() {
        val clock = AtomicLong(0)
        val lines = mutableListOf<String>()
        TurnOutputPump(
            stream = NeverEndingStream(),
            isCancelled = { false },
            isProcessAlive = { false },
            onLine = { lines += it },
            pollMs = 5,
            drainAfterExitMs = 100,
            clock = { clock.addAndGet(60) },
        ).run()
        assertTrue("nothing should be emitted", lines.isEmpty())
    }

    /** Blocks in read() until closed, the way a leaked grandchild holds a pipe. */
    private class NeverEndingStream : InputStream() {
        private val closed = AtomicBoolean(false)

        override fun read(): Int {
            while (!closed.get()) Thread.sleep(10)
            return -1
        }

        override fun close() {
            closed.set(true)
        }
    }
}
