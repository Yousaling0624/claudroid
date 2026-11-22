package app.claudroid.remote

import app.claudroid.engine.CancelReason
import app.claudroid.engine.EngineEvent
import app.claudroid.engine.PermissionDecision
import app.claudroid.engine.StreamParser
import app.claudroid.engine.TurnBackend
import app.claudroid.engine.TurnRequest
import java.util.concurrent.atomic.AtomicLong

/**
 * Remote executor: starts a turn on the daemon, replays its raw CLI lines through the
 * same [StreamParser] as the local engine, and turns permission asks into first-class
 * [EngineEvent.PermissionRequest]s.
 */
class RemoteTurnBackend(
    private val manager: RemoteHostManager,
    private val hostId: String,
    private val cwd: String,
    override val label: String,
    override val modelLabel: String?,
) : TurnBackend {

    private val generation = AtomicLong(0)
    @Volatile private var reason: CancelReason? = null
    @Volatile private var stopped = false

    override fun beginTurn(): Long {
        reason = null
        stopped = false
        return generation.get()
    }

    override fun isCancelled(token: Long): Boolean = stopped || generation.get() > token

    override fun cancel(reason: CancelReason) {
        this.reason = reason
        stopped = true
        generation.incrementAndGet()
        manager.cancelCurrentTurn(hostId)
    }

    override fun cancelReason(): CancelReason? = reason

    override suspend fun runTurn(
        request: TurnRequest,
        cancelToken: Long,
        onEvent: (EngineEvent) -> Unit,
    ): Int {
        if (isCancelled(cancelToken)) return -2
        val parser = StreamParser()
        return manager.runTurn(
            hostId = hostId,
            cwd = cwd,
            request = request,
            isCancelled = { isCancelled(cancelToken) },
            onEvent = { event ->
                when (event) {
                    is RemoteEvent.Cli -> parser.parse(event.line).forEach(onEvent)
                    is RemoteEvent.Permission -> onEvent(
                        EngineEvent.PermissionRequest(
                            requestId = event.requestId,
                            toolName = event.toolName,
                            inputJson = event.input?.toString() ?: "{}",
                            suggestionsJson = event.suggestions?.toString(),
                            expiresAt = event.expiresAt,
                            title = event.title ?: event.displayName,
                            description = event.description ?: event.decisionReason,
                        )
                    )
                    is RemoteEvent.Turn -> if (event.phase == "error") {
                        onEvent(EngineEvent.Failed(event.error ?: "主机任务执行失败。"))
                    }
                    else -> Unit
                }
            },
            onFailed = { message -> onEvent(EngineEvent.Failed(message)) },
        )
    }

    override suspend fun respondPermission(requestId: String, decision: PermissionDecision) {
        manager.respondPermission(requestId, decision)
    }
}
