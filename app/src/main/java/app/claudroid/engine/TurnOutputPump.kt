package app.claudroid.engine

import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Drains the CLI's stdout on a helper thread so a stubborn child can never wedge a turn.
 *
 * `readLine()` only returns at EOF — that is, when *every* process holding the pipe's
 * write end is gone. In root mode the direct child is only `su`; killing it leaves the
 * real CLI alive and still holding stdout, so a turn thread that reads inline would
 * block forever after Stop. The same happens when a tool command outlives the CLI.
 *
 * Lines are handed over through a small bounded queue. The consumer only waits while the
 * turn is alive; on stop it returns immediately, and once the CLI itself has exited it
 * gives the reader a short drain window for output still sitting in the pipe.
 *
 * The reader thread is a daemon on purpose: if a grandchild keeps the pipe open, the
 * only way out is to abandon it (it dies when that process finally exits).
 */
internal class TurnOutputPump(
    private val stream: InputStream,
    private val isCancelled: () -> Boolean,
    private val isProcessAlive: () -> Boolean,
    private val onLine: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val pollMs: Long = 100,
    private val drainAfterExitMs: Long = 1_500,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    fun run() {
        val queue = ArrayBlockingQueue<String>(QUEUE_LINES)
        val reader = Thread({
            runCatching {
                stream.bufferedReader().use { buffered ->
                    while (true) {
                        val line = buffered.readLine() ?: break
                        // Bounded: after a stop nobody drains any more; drop the rest of a
                        // long stream instead of buffering it in memory.
                        queue.offer(line)
                    }
                }
            }.onFailure { t ->
                if (!isCancelled()) onFailure(t)
            }
        }, "claudroid-stdout").apply {
            isDaemon = true
            start()
        }

        var drainUntil = 0L
        while (true) {
            val line = queue.poll(pollMs, TimeUnit.MILLISECONDS)
            // Drop late output: the UI already closed this turn.
            if (isCancelled()) return
            if (line != null) {
                onLine(line)
            } else if (!reader.isAlive) {
                return
            }
            if (!isProcessAlive()) {
                val now = clock()
                if (drainUntil == 0L) {
                    drainUntil = now + drainAfterExitMs
                } else if (now >= drainUntil) {
                    // The CLI is gone but something it spawned still holds the pipe open.
                    return
                }
            }
        }
    }

    private companion object {
        /** Enough for a bursty tool result; the consumer normally keeps it near empty. */
        const val QUEUE_LINES = 512
    }
}
