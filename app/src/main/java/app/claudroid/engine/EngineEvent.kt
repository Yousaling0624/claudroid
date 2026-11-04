package app.claudroid.engine

/** Normalised events decoded from the CLI's `--output-format stream-json` stream. */
sealed interface EngineEvent {
    data class SessionInit(val sessionId: String, val model: String?, val tools: List<String>) : EngineEvent
    data class TextDelta(val text: String) : EngineEvent
    data class ThinkingDelta(val text: String) : EngineEvent
    data class ToolUse(val id: String, val name: String, val inputJson: String) : EngineEvent
    data class ToolResult(val id: String, val output: String, val isError: Boolean) : EngineEvent
    data class TurnDone(
        val resultText: String,
        val costUsd: Double?,
        val durationMs: Long?,
        val isError: Boolean,
        val sessionId: String?,
        val numTurns: Int?,
        /** e.g. "success", "error_max_turns"; null on older CLI builds. */
        val subtype: String? = null,
    ) : EngineEvent
    data class Log(val line: String, val isError: Boolean = false) : EngineEvent
    data class Failed(val message: String) : EngineEvent
    data class Exited(val code: Int) : EngineEvent
}
