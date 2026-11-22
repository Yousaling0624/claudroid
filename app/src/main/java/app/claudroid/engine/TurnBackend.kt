package app.claudroid.engine

import app.claudroid.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Everything a backend needs to run one turn; nothing here carries phone credentials. */
data class TurnRequest(
    val prompt: String,
    val resumeSessionId: String?,
    /** One of `default`, `acceptEdits`, `plan`, `bypassPermissions`. */
    val permissionMode: String,
    val model: String? = null,
)

/** A single allow/deny answer for a forwarded tool-permission prompt. */
data class PermissionDecision(
    val allow: Boolean,
    val always: Boolean = false,
    val message: String? = null,
)

/**
 * One conversation executor. Local turns wrap [ClaudeEngine]; remote turns speak to
 * `claudroid-bridge`. `ChatController` only sees this interface, so persistence,
 * streaming events and the transcript remain identical for both.
 */
interface TurnBackend {
    /** Human label for the status line, e.g. `本机` or a host name. */
    val label: String

    /** Frozen model signature for this turn; null when the backend does not know it. */
    val modelLabel: String?

    fun beginTurn(): Long
    fun isCancelled(token: Long): Boolean
    fun cancel(reason: CancelReason = CancelReason.USER)
    fun cancelReason(): CancelReason?
    suspend fun runTurn(request: TurnRequest, cancelToken: Long, onEvent: (EngineEvent) -> Unit): Int
    suspend fun respondPermission(requestId: String, decision: PermissionDecision)
}

/** Existing single-process CLI path, unchanged apart from the interface. */
class LocalTurnBackend(
    private val engine: ClaudeEngine,
    private val settings: AppSettings,
    private val plan: ClaudeEngine.LaunchPlan,
    private val workspace: File,
    override val modelLabel: String?,
) : TurnBackend {

    override val label: String = "本机"

    override fun beginTurn(): Long = engine.beginTurn()

    override fun isCancelled(token: Long): Boolean = engine.isCancelled(token)

    override fun cancel(reason: CancelReason) = engine.cancel(reason)

    override fun cancelReason(): CancelReason? = engine.cancelReason()

    override suspend fun runTurn(
        request: TurnRequest,
        cancelToken: Long,
        onEvent: (EngineEvent) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        engine.runTurn(
            prompt = request.prompt,
            resumeSessionId = request.resumeSessionId,
            settings = settings,
            plan = plan,
            workspace = workspace,
            cancelToken = cancelToken,
            onEvent = onEvent,
        )
    }

    override suspend fun respondPermission(requestId: String, decision: PermissionDecision) = Unit
}
